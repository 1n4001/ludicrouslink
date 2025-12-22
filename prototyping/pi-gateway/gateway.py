#!/usr/bin/env python3
"""
Raspberry Pi Gateway for HStreamer v2
Receives RTMP stream FROM Android and forwards to web clients via WebSocket
Advertises itself via mDNS/Zeroconf for automatic discovery
Includes video and audio streaming support
"""

import asyncio
import gi
import json
import logging
import base64
import time
from typing import Set
from queue import Queue
from threading import Thread, Event
from zeroconf import ServiceInfo, Zeroconf
import socket
from pathlib import Path
from aiohttp import web
import subprocess
from collections import deque

gi.require_version('Gst', '1.0')
gi.require_version('GstApp', '1.0')
from gi.repository import Gst, GLib, GstApp

# Initialize GStreamer
Gst.init(None)

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler('gateway.log', mode='a')
    ]
)
logger = logging.getLogger(__name__)


class MpegTsReceiver:
    """Pure Python MPEG-TS parser for SRT/TCP/UDP streams - NO GSTREAMER!

    Parses MPEG-TS packets to extract H.264 NAL units directly.
    Much lower latency than GStreamer since there's no buffering.
    Supports SRT (reliable), TCP (connection-oriented), and UDP (connectionless) transports.
    """

    def __init__(self, port: int, frame_queue: Queue, transport: str = 'srt'):
        self.port = port
        self.transport = transport  # 'srt', 'tcp', or 'udp'
        self.frame_queue = frame_queue
        self.running = False
        self.thread = None
        self.video_pid = None  # Will be discovered from PMT
        self.pmt_pid = None    # Will be discovered from PAT
        self.pes_buffer = b''  # Buffer for assembling PES packets
        self.frame_count = 0
        self.sps = None  # H.264 SPS (Sequence Parameter Set) - extracted from stream
        self.pps = None  # H.264 PPS (Picture Parameter Set) - extracted from stream
        self.codec_data_extracted = False  # Track if we've extracted SPS/PPS
        self.ts_packet_count = 0  # Debug: count TS packets
        self.video_pes_count = 0  # Debug: count video PES packets
        self.last_frame_time = 0  # Track last frame received for health monitoring
        self.start_time = 0  # Track when stream started

    def start(self):
        """Start receiver thread"""
        self.running = True
        self.start_time = time.time()
        self.last_frame_time = time.time()
        self.thread = Thread(target=self._receive_loop, daemon=True)
        self.thread.start()
        logger.info(f"Python MPEG-TS parser started on {self.transport.upper()} port {self.port}")

    def stop(self):
        """Stop receiver"""
        self.running = False
        if self.thread:
            self.thread.join(timeout=2.0)
        logger.info("Python MPEG-TS parser stopped")

    def _receive_loop(self):
        """Main loop - receives TCP/UDP/SRT data and parses MPEG-TS"""
        if self.transport == 'srt':
            self._srt_receive_loop()
        elif self.transport == 'tcp':
            self._tcp_receive_loop()
        else:
            self._udp_receive_loop()

    def _srt_receive_loop(self):
        """SRT server - uses ffmpeg to receive SRT stream and parse MPEG-TS"""
        logger.info(f"Starting SRT server on port {self.port} (using ffmpeg)")

        # Use ffmpeg to receive SRT and output raw MPEG-TS
        ffmpeg_cmd = [
            'ffmpeg',
            '-loglevel', 'warning',
            '-i', f'srt://0.0.0.0:{self.port}?mode=listener',  # SRT listener mode
            '-c', 'copy',  # Copy streams without re-encoding
            '-f', 'mpegts',  # Output MPEG-TS format
            'pipe:1'  # Output to stdout
        ]

        try:
            process = subprocess.Popen(
                ffmpeg_cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                bufsize=0
            )

            logger.info(f"SRT server listening on 0.0.0.0:{self.port} (ffmpeg PID: {process.pid})")
            logger.info("Waiting for SRT connection from Android...")

            buffer = b''
            while self.running and process.poll() is None:
                try:
                    # Read from ffmpeg stdout
                    chunk = process.stdout.read(8192)
                    if not chunk:
                        break

                    buffer += chunk

                    # Process complete 188-byte TS packets
                    while len(buffer) >= 188:
                        if buffer[0] == 0x47:  # Sync byte
                            self._parse_ts_packet(buffer[:188])
                            buffer = buffer[188:]
                        else:
                            # Lost sync - search for next sync byte
                            sync_pos = buffer.find(b'\x47', 1)
                            if sync_pos > 0:
                                logger.warning(f"Lost TS sync, skipped {sync_pos} bytes")
                                buffer = buffer[sync_pos:]
                            else:
                                buffer = b''

                except Exception as e:
                    if self.running:
                        logger.error(f"SRT receive error: {e}")
                    break

            # Clean up
            process.terminate()
            try:
                process.wait(timeout=2.0)
            except subprocess.TimeoutExpired:
                process.kill()

            logger.info("SRT server stopped")

        except FileNotFoundError:
            logger.error("ffmpeg not found! Install with: sudo apt-get install ffmpeg")
        except Exception as e:
            logger.error(f"SRT server error: {e}")

    def _tcp_receive_loop(self):
        """TCP server - accepts connection and reads MPEG-TS stream"""
        server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server_sock.bind(('0.0.0.0', self.port))
        server_sock.listen(1)
        server_sock.settimeout(1.0)  # Non-blocking accept

        logger.info(f"TCP server listening on 0.0.0.0:{self.port}")
        logger.info("Waiting for MPEG-TS stream from Android...")

        while self.running:
            try:
                # Accept connection
                client_sock, client_addr = server_sock.accept()
                logger.info(f"TCP client connected from {client_addr}")

                client_sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 2 * 1024 * 1024)
                client_sock.settimeout(5.0)

                # Read MPEG-TS stream from client
                buffer = b''
                while self.running:
                    try:
                        chunk = client_sock.recv(8192)  # Read in chunks
                        if not chunk:
                            logger.warning("TCP client disconnected")
                            break

                        buffer += chunk

                        # Process complete 188-byte TS packets
                        while len(buffer) >= 188:
                            # Look for sync byte
                            if buffer[0] == 0x47:
                                self._parse_ts_packet(buffer[:188])
                                buffer = buffer[188:]
                            else:
                                # Lost sync - search for next sync byte
                                sync_pos = buffer.find(b'\x47', 1)
                                if sync_pos > 0:
                                    logger.warning(f"Lost TS sync, skipped {sync_pos} bytes")
                                    buffer = buffer[sync_pos:]
                                else:
                                    buffer = b''

                    except socket.timeout:
                        continue
                    except Exception as e:
                        logger.error(f"TCP receive error: {e}")
                        break

                client_sock.close()
                logger.info("TCP client socket closed")

            except socket.timeout:
                continue
            except Exception as e:
                if self.running:
                    logger.error(f"TCP accept error: {e}")
                time.sleep(0.1)

        server_sock.close()
        logger.info("TCP server socket closed")

    def _udp_receive_loop(self):
        """UDP receiver - receives datagrams and parses MPEG-TS"""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 2 * 1024 * 1024)  # 2MB buffer
        sock.bind(('0.0.0.0', self.port))
        sock.settimeout(1.0)  # 1 second timeout for clean shutdown

        logger.info(f"UDP socket listening on 0.0.0.0:{self.port}")
        logger.info("Waiting for MPEG-TS stream from Android...")

        while self.running:
            try:
                data, addr = sock.recvfrom(65536)  # Max UDP datagram size
                self._parse_ts_datagram(data)
            except socket.timeout:
                continue
            except Exception as e:
                logger.error(f"Error receiving UDP: {e}")

        sock.close()

    def _parse_ts_datagram(self, data: bytes):
        """Parse UDP datagram containing multiple MPEG-TS packets (188 bytes each)"""
        offset = 0
        while offset + 188 <= len(data):
            ts_packet = data[offset:offset + 188]
            if ts_packet[0] != 0x47:  # Sync byte
                logger.warning(f"Invalid MPEG-TS sync byte: 0x{ts_packet[0]:02x}")
                offset += 1  # Try to resync
                continue

            self._parse_ts_packet(ts_packet)
            offset += 188

    def _parse_ts_packet(self, packet: bytes):
        """Parse single 188-byte MPEG-TS packet"""
        self.ts_packet_count += 1

        # Parse header (first 4 bytes)
        sync_byte = packet[0]
        header_byte1 = packet[1]
        header_byte2 = packet[2]
        header_byte3 = packet[3]

        # Extract PID (13 bits from bytes 1-2)
        pid = ((header_byte1 & 0x1F) << 8) | header_byte2

        # Check payload_unit_start_indicator (bit 6 of byte 1)
        payload_unit_start = bool(header_byte1 & 0x40)

        # Check adaptation field control (bits 4-5 of byte 3)
        adaptation_field_control = (header_byte3 >> 4) & 0x03
        has_adaptation_field = adaptation_field_control in (0x02, 0x03)
        has_payload = adaptation_field_control in (0x01, 0x03)

        if not has_payload:
            return

        # Skip adaptation field if present
        payload_offset = 4
        if has_adaptation_field:
            adaptation_field_length = packet[4]
            payload_offset += 1 + adaptation_field_length

        if payload_offset >= 188:
            return

        payload = packet[payload_offset:]

        # Handle different PID types
        if pid == 0:  # PAT (Program Association Table)
            self._parse_pat(payload)
        elif self.pmt_pid and pid == self.pmt_pid:  # PMT (Program Map Table)
            self._parse_pmt(payload)
        elif self.video_pid and pid == self.video_pid:  # Video PES
            self._parse_video_pes(payload, payload_unit_start)

        # Log stats every 1000 TS packets
        if self.ts_packet_count % 1000 == 0:
            logger.info(f"Stats: {self.ts_packet_count} TS packets, {self.video_pes_count} PES packets, {self.frame_count} frames queued")

    def _parse_pat(self, payload: bytes):
        """Parse PAT to find PMT PID"""
        if len(payload) < 8:
            return

        # Skip pointer field if present
        pointer = payload[0]
        offset = 1 + pointer

        if offset + 8 > len(payload):
            return

        # Extract PMT PID (usually at offset+10, PID 13 bits)
        if offset + 12 <= len(payload):
            pmt_pid = ((payload[offset + 10] & 0x1F) << 8) | payload[offset + 11]
            if self.pmt_pid != pmt_pid:
                self.pmt_pid = pmt_pid
                logger.info(f"PAT: Found PMT PID = {pmt_pid}")

    def _parse_pmt(self, payload: bytes):
        """Parse PMT to find video stream PID"""
        if len(payload) < 12:
            return

        # Skip pointer field if present
        pointer = payload[0]
        offset = 1 + pointer

        if offset + 12 > len(payload):
            return

        # Check table ID (should be 0x02 for PMT)
        table_id = payload[offset]
        if table_id != 0x02:
            return

        # Parse program info length (at offset + 10, bytes 10-11 of PMT header)
        if offset + 12 > len(payload):
            return
        program_info_length = ((payload[offset + 10] & 0x0F) << 8) | payload[offset + 11]

        # Streams start at offset + 12 + program_info_length
        stream_offset = offset + 12 + program_info_length

        # Parse stream descriptors
        while stream_offset + 5 <= len(payload):
            stream_type = payload[stream_offset]
            stream_pid = ((payload[stream_offset + 1] & 0x1F) << 8) | payload[stream_offset + 2]
            es_info_length = ((payload[stream_offset + 3] & 0x0F) << 8) | payload[stream_offset + 4]

            # H.264 video stream type = 0x1B
            if stream_type == 0x1B and self.video_pid != stream_pid:
                self.video_pid = stream_pid
                logger.info(f"PMT: Found H.264 video PID = {stream_pid}")

            stream_offset += 5 + es_info_length

    def _parse_video_pes(self, payload: bytes, unit_start: bool):
        """Parse PES packet containing H.264 data"""
        if unit_start:
            self.video_pes_count += 1

            # Start of new PES packet - process any buffered data first
            if self.pes_buffer:
                self._extract_h264_nals(self.pes_buffer)
                self.pes_buffer = b''

            # Parse PES header
            if len(payload) < 9:
                return

            # Check PES start code (0x000001)
            if payload[0:3] != b'\x00\x00\x01':
                return

            # Extract PES header length
            pes_header_length = payload[8]
            pes_payload_start = 9 + pes_header_length

            if pes_payload_start >= len(payload):
                return

            # Start buffering the H.264 data
            self.pes_buffer = payload[pes_payload_start:]
        else:
            # Continuation of PES packet
            self.pes_buffer += payload

    def _extract_h264_nals(self, data: bytes):
        """Extract H.264 NAL units from PES payload and queue them"""
        if len(data) < 4:
            return

        # Parse NAL units to extract SPS/PPS for WebCodecs initialization
        # NAL units start with 0x00000001 or 0x000001
        offset = 0
        while offset < len(data) - 4:
            # Look for start code (0x000001 or 0x00000001)
            if data[offset:offset+3] == b'\x00\x00\x01':
                nal_start = offset + 3
            elif data[offset:offset+4] == b'\x00\x00\x00\x01':
                nal_start = offset + 4
            else:
                offset += 1
                continue

            # Find next start code to determine NAL unit length
            nal_end = len(data)
            for i in range(nal_start + 1, len(data) - 3):
                if data[i:i+3] == b'\x00\x00\x01' or data[i:i+4] == b'\x00\x00\x00\x01':
                    nal_end = i
                    break

            if nal_start < len(data):
                nal_unit_type = data[nal_start] & 0x1F  # Lower 5 bits
                nal_unit = data[nal_start:nal_end]

                # Extract SPS (type 7) and PPS (type 8)
                if nal_unit_type == 7 and not self.sps:
                    self.sps = base64.b64encode(nal_unit).decode('utf-8')
                    logger.info(f"Extracted SPS (length: {len(nal_unit)} bytes)")
                elif nal_unit_type == 8 and not self.pps:
                    self.pps = base64.b64encode(nal_unit).decode('utf-8')
                    logger.info(f"Extracted PPS (length: {len(nal_unit)} bytes)")

                if self.sps and self.pps and not self.codec_data_extracted:
                    self.codec_data_extracted = True
                    logger.info("H.264 codec data (SPS+PPS) extracted successfully")

            offset = nal_end

        # Send entire PES payload as raw bytes (broadcaster will base64 encode it)
        # Use non-blocking put to avoid blocking UDP receiver if queue is full
        try:
            self.frame_queue.put_nowait(data)
            self.frame_count += 1
            self.last_frame_time = time.time()  # Update health timestamp

            if self.frame_count % 30 == 0:
                logger.info(f"Parsed {self.frame_count} H.264 frames")
        except:
            # Queue full - drop frame (better to drop than block UDP receiver)
            pass


class GStreamerUdpReceiver:
    """GStreamer-based UDP receiver that processes MPEG-TS stream"""

    def __init__(self, udp_port: int, frame_queue: Queue, audio_queue: Queue = None, video_mode: str = 'h264'):
        self.udp_port = udp_port  # UDP port for receiving MPEG-TS packets
        self.frame_queue = frame_queue
        self.audio_queue = audio_queue  # Optional - None for video-only mode
        self.video_mode = video_mode  # 'h264' or 'jpeg'
        self.pipeline = None
        self.loop = None
        self.thread = None
        self.running = False
        self.retry_count = 0
        self.video_frame_count = 0  # Track video frames received
        self.audio_frame_count = 0  # Track audio frames received
        self.stream_active = False  # Track if stream is currently active
        self.pipeline_ready = Event()  # Signal when pipeline is ready to accept connections
        self.sps = None  # H.264 SPS (Sequence Parameter Set) for WebCodecs
        self.pps = None  # H.264 PPS (Picture Parameter Set) for WebCodecs
        self.codec_data_extracted = False  # Track if we've extracted SPS/PPS

    def start(self):
        """Start the GStreamer UDP receiver pipeline"""
        self.running = True

        # Start GStreamer pipeline to receive UDP
        self.thread = Thread(target=self._run_pipeline, daemon=True)
        self.thread.start()
        logger.info("GStreamer UDP receiver started")

    def _run_pipeline(self):
        """Run the GStreamer pipeline with retry logic"""

        # Wait for UDP stream from Android
        logger.info("Waiting for UDP stream from Android device...")
        logger.info(f"UDP listening on port: {self.udp_port}")
        logger.info(f"Android devices should connect to: udp://<gateway-ip>:{self.udp_port}")

        # Run continuously for daemon operation (no retry limit)
        while self.running:
            try:
                # Choose pipeline based on video mode
                if self.video_mode == 'h264':
                    # H.264 passthrough pipeline via UDP/MPEG-TS - ULTRA LOW LATENCY (no audio)
                    # Receives MPEG-TS packets over UDP from Android device
                    # NOTE: tsdemux pads are created dynamically, must link via pad-added callback
                    pipeline_str = (
                        f"udpsrc port={self.udp_port} "
                        "buffer-size=524288 "
                        "retrieve-sender-address=false ! "
                        "tsdemux name=demux "
                        "latency=0 "
                        "program-number=-1 "
                        "parse-private-sections=false "
                        "ignore-pcr=true "
                    )
                    logger.info(f"Using H.264 passthrough mode via UDP (ULTRA LOW LATENCY - port {self.udp_port})")
                else:
                    # JPEG pipeline via UDP/MPEG-TS - LOW LATENCY (no audio, legacy fallback)
                    # Receives MPEG-TS packets over UDP from Android device
                    # NOTE: tsdemux pads are created dynamically, must link via pad-added callback
                    pipeline_str = (
                        f"udpsrc port={self.udp_port} "
                        "buffer-size=2097152 "
                        "retrieve-sender-address=false ! "
                        "queue max-size-buffers=200 max-size-time=0 max-size-bytes=0 leaky=downstream ! "
                        "tsdemux name=demux latency=0 "
                        "program-number=-1 "
                        "parse-private-sections=false "
                    )
                    logger.info(f"Using JPEG mode via UDP (LOW LATENCY - port {self.udp_port})")

                logger.info(f"Creating GStreamer pipeline (attempt {self.retry_count + 1})...")
                self.pipeline = Gst.parse_launch(pipeline_str)

                # Create downstream elements for dynamic linking
                if self.video_mode == 'h264':
                    # Create video processing chain - ULTRA MINIMAL BUFFERING
                    # Try without any queue for absolute minimum latency
                    h264parse = Gst.ElementFactory.make("h264parse", "h264parse")
                    h264parse.set_property("config-interval", -1)

                    capsfilter = Gst.ElementFactory.make("capsfilter", "capsfilter")
                    caps = Gst.Caps.from_string("video/x-h264,stream-format=byte-stream,alignment=au")
                    capsfilter.set_property("caps", caps)

                    videosink = Gst.ElementFactory.make("appsink", "videosink")
                    videosink.set_property("emit-signals", True)
                    videosink.set_property("max-buffers", 1)
                    videosink.set_property("drop", True)
                    videosink.set_property("sync", False)
                    videosink.connect('new-sample', self._on_new_video_sample)

                    # Add elements to pipeline
                    self.pipeline.add(h264parse)
                    self.pipeline.add(capsfilter)
                    self.pipeline.add(videosink)

                    # Link downstream elements - NO QUEUE!
                    h264parse.link(capsfilter)
                    capsfilter.link(videosink)

                    logger.info("Created downstream video processing chain")
                else:
                    # Create JPEG processing chain
                    videoqueue = Gst.ElementFactory.make("queue", "videoqueue")
                    videoqueue.set_property("max-size-buffers", 2)
                    videoqueue.set_property("max-size-time", 0)
                    videoqueue.set_property("max-size-bytes", 0)
                    videoqueue.set_property("leaky", 2)  # 2 = downstream

                    h264parse = Gst.ElementFactory.make("h264parse", "h264parse")
                    avdec_h264 = Gst.ElementFactory.make("avdec_h264", "avdec_h264")
                    videoconvert = Gst.ElementFactory.make("videoconvert", "videoconvert")

                    capsfilter1 = Gst.ElementFactory.make("capsfilter", "capsfilter1")
                    caps1 = Gst.Caps.from_string("video/x-raw,format=RGB")
                    capsfilter1.set_property("caps", caps1)

                    videoscale = Gst.ElementFactory.make("videoscale", "videoscale")

                    capsfilter2 = Gst.ElementFactory.make("capsfilter", "capsfilter2")
                    caps2 = Gst.Caps.from_string("video/x-raw,width=1280,height=720")
                    capsfilter2.set_property("caps", caps2)

                    jpegenc = Gst.ElementFactory.make("jpegenc", "jpegenc")
                    jpegenc.set_property("quality", 85)

                    videosink = Gst.ElementFactory.make("appsink", "videosink")
                    videosink.set_property("emit-signals", True)
                    videosink.set_property("max-buffers", 1)
                    videosink.set_property("drop", True)
                    videosink.set_property("sync", False)
                    videosink.connect('new-sample', self._on_new_video_sample)

                    # Add elements to pipeline
                    self.pipeline.add(videoqueue)
                    self.pipeline.add(h264parse)
                    self.pipeline.add(avdec_h264)
                    self.pipeline.add(videoconvert)
                    self.pipeline.add(capsfilter1)
                    self.pipeline.add(videoscale)
                    self.pipeline.add(capsfilter2)
                    self.pipeline.add(jpegenc)
                    self.pipeline.add(videosink)

                    # Link downstream elements
                    videoqueue.link(h264parse)
                    h264parse.link(avdec_h264)
                    avdec_h264.link(videoconvert)
                    videoconvert.link(capsfilter1)
                    capsfilter1.link(videoscale)
                    videoscale.link(capsfilter2)
                    capsfilter2.link(jpegenc)
                    jpegenc.link(videosink)

                    logger.info("Created downstream JPEG processing chain")

                # Connect tsdemux pad-added for dynamic linking
                demux = self.pipeline.get_by_name('demux')
                if demux:
                    demux.connect('pad-added', self._on_demux_pad_added)
                    logger.info("Connected tsdemux pad-added callback for dynamic linking")

                # Get udpsrc for monitoring
                udpsrc = self.pipeline.get_by_name('udpsrc0')
                if udpsrc:
                    logger.info(f"udpsrc configured: listening on port {self.udp_port}")

                # Create main loop
                self.loop = GLib.MainLoop()

                # Set up bus message handler to catch errors
                bus = self.pipeline.get_bus()
                bus.add_signal_watch()
                bus.connect('message::error', self._on_bus_error)
                bus.connect('message::eos', self._on_bus_eos)
                bus.connect('message::warning', self._on_bus_warning)
                bus.connect('message::state-changed', self._on_state_changed)
                bus.connect('message::async-done', self._on_async_done)
                bus.connect('message::info', self._on_bus_info)

                # Start pipeline
                ret = self.pipeline.set_state(Gst.State.PLAYING)

                if ret == Gst.StateChangeReturn.FAILURE:
                    logger.warning(f"Pipeline failed to start (attempt {self.retry_count + 1})")
                    self.retry_count += 1
                    self._cleanup()
                    time.sleep(1)
                    continue

                if ret == Gst.StateChangeReturn.NO_PREROLL:
                    logger.info("Pipeline in NO_PREROLL state (live source)")

                # Wait for state change to complete
                ret, state, pending = self.pipeline.get_state(timeout=5 * Gst.SECOND)

                if ret == Gst.StateChangeReturn.SUCCESS or ret == Gst.StateChangeReturn.NO_PREROLL or ret == Gst.StateChangeReturn.ASYNC:
                    logger.info(f"Pipeline started successfully!")
                    if ret == Gst.StateChangeReturn.ASYNC:
                        logger.info("Pipeline in ASYNC state (waiting for UDP stream)")
                    logger.info(f"Waiting for Android device to send UDP stream on port {self.udp_port}...")

                    # Signal that pipeline is ready to accept connections
                    self.pipeline_ready.set()

                    # Run the loop (blocking until quit() is called)
                    self.loop.run()

                    # When loop exits, cleanup and recreate pipeline for next connection
                    logger.info("GStreamer pipeline stopped, recreating...")
                    # Note: pipeline_ready was already cleared in _on_bus_eos()
                    self._cleanup()
                    time.sleep(1)  # Brief delay before recreation
                    continue  # Go back to start of while loop to recreate pipeline
                else:
                    logger.warning(f"Pipeline state change unsuccessful: {ret}")
                    self.retry_count += 1
                    self._cleanup()
                    time.sleep(1)

            except Exception as e:
                logger.error(f"Pipeline error (attempt {self.retry_count + 1}): {e}")
                self.retry_count += 1
                self._cleanup()
                time.sleep(1)

    def _on_new_video_sample(self, appsink):
        """Callback for new video frame from GStreamer"""
        try:
            sample = appsink.emit('pull-sample')
            if sample:
                # Extract SPS/PPS from caps on first frame (for H.264 mode)
                if not self.codec_data_extracted and self.video_mode == 'h264':
                    try:
                        caps = sample.get_caps()
                        if caps:
                            structure = caps.get_structure(0)
                            # In byte-stream format, we need to extract SPS/PPS from NAL units
                            # The h264parse element should provide codec_data in AVC format
                            codec_data = structure.get_value('codec_data')
                            if codec_data:
                                # codec_data is in AVC format (avcC box)
                                # Extract SPS and PPS from it
                                self._extract_sps_pps_from_codec_data(codec_data)
                                self.codec_data_extracted = True
                                logger.info("Extracted SPS/PPS from H.264 stream for WebCodecs")
                    except Exception as e:
                        logger.warning(f"Could not extract codec_data from caps: {e}")

                buffer = sample.get_buffer()

                # Extract buffer data
                success, map_info = buffer.map(Gst.MapFlags.READ)
                if success:
                    video_data = bytes(map_info.data)
                    buffer.unmap(map_info)

                    self.video_frame_count += 1

                    # Mark stream as active and log first frame
                    if not self.stream_active:
                        self.stream_active = True
                        logger.info("=== STREAM CONNECTED: Receiving video data ===")

                    # Log first few frames to confirm reception
                    if self.video_frame_count <= 3:
                        logger.info(f"Received video frame #{self.video_frame_count} ({len(video_data)} bytes)")

                    # For H.264 mode, extract SPS/PPS from NAL units if not yet extracted
                    if not self.codec_data_extracted and self.video_mode == 'h264':
                        self._extract_sps_pps_from_nal_units(video_data)

                    # Put frame in queue (non-blocking)
                    if self.frame_queue.qsize() < 10:
                        self.frame_queue.put(video_data)
                    else:
                        logger.warning(f"Frame queue full, dropping frame")

                return Gst.FlowReturn.OK
            else:
                logger.warning("No sample received from appsink")
                return Gst.FlowReturn.OK
        except Exception as e:
            logger.error(f"Error processing video sample: {e}")
            return Gst.FlowReturn.ERROR

    def _on_new_audio_sample(self, appsink):
        """Callback for new audio packet from GStreamer"""
        try:
            sample = appsink.emit('pull-sample')
            if sample:
                buffer = sample.get_buffer()

                # Extract buffer data
                success, map_info = buffer.map(Gst.MapFlags.READ)
                if success:
                    opus_data = bytes(map_info.data)
                    buffer.unmap(map_info)

                    self.audio_frame_count += 1

                    # Log first few audio packets to confirm reception
                    if self.audio_frame_count <= 3:
                        logger.info(f"Received audio packet #{self.audio_frame_count} ({len(opus_data)} bytes)")

                    # Put audio packet in queue (non-blocking) - only if audio enabled
                    if self.audio_queue and self.audio_queue.qsize() < 10:
                        self.audio_queue.put(opus_data)

                return Gst.FlowReturn.OK
        except Exception as e:
            logger.error(f"Error processing audio sample: {e}")
            return Gst.FlowReturn.ERROR

    def _extract_sps_pps_from_nal_units(self, data):
        """Extract SPS and PPS from H.264 NAL units in byte-stream format"""
        try:
            i = 0
            while i < len(data) - 4:
                # Look for start code (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
                if data[i:i+4] == b'\x00\x00\x00\x01':
                    start = i + 4
                    nal_type = data[start] & 0x1F

                    # Find next start code
                    j = start + 1
                    while j < len(data) - 4:
                        if data[j:j+4] == b'\x00\x00\x00\x01' or data[j:j+3] == b'\x00\x00\x01':
                            break
                        j += 1

                    # Extract NAL unit (without start code)
                    nal_unit = data[start:j]

                    # SPS = NAL type 7, PPS = NAL type 8
                    if nal_type == 7 and not self.sps:
                        self.sps = nal_unit
                        logger.info(f"Extracted SPS ({len(self.sps)} bytes)")
                    elif nal_type == 8 and not self.pps:
                        self.pps = nal_unit
                        logger.info(f"Extracted PPS ({len(self.pps)} bytes)")

                    if self.sps and self.pps:
                        self.codec_data_extracted = True
                        logger.info("SPS and PPS extraction complete")
                        return

                    i = j
                elif data[i:i+3] == b'\x00\x00\x01':
                    i += 3
                else:
                    i += 1

        except Exception as e:
            logger.error(f"Error extracting SPS/PPS: {e}")

    def _extract_sps_pps_from_codec_data(self, codec_data):
        """Extract SPS and PPS from AVC codec_data (avcC box)"""
        # codec_data is GstBuffer, need to map it
        try:
            success, map_info = codec_data.map(Gst.MapFlags.READ)
            if success:
                data = bytes(map_info.data)
                codec_data.unmap(map_info)

                # Parse avcC format
                # Skip first 5 bytes (configurationVersion, AVCProfileIndication,
                # profile_compatibility, AVCLevelIndication, lengthSizeMinusOne)
                if len(data) > 5:
                    num_sps = data[5] & 0x1F
                    idx = 6

                    # Extract SPS
                    if num_sps > 0 and idx + 2 <= len(data):
                        sps_length = (data[idx] << 8) | data[idx + 1]
                        idx += 2
                        if idx + sps_length <= len(data):
                            self.sps = data[idx:idx + sps_length]
                            logger.info(f"Extracted SPS from codec_data ({len(self.sps)} bytes)")
                            idx += sps_length

                    # Extract PPS
                    if idx < len(data):
                        num_pps = data[idx]
                        idx += 1
                        if num_pps > 0 and idx + 2 <= len(data):
                            pps_length = (data[idx] << 8) | data[idx + 1]
                            idx += 2
                            if idx + pps_length <= len(data):
                                self.pps = data[idx:idx + pps_length]
                                logger.info(f"Extracted PPS from codec_data ({len(self.pps)} bytes)")
        except Exception as e:
            logger.error(f"Error extracting from codec_data: {e}")

    def _on_demux_pad_added(self, element, pad):
        """Callback when tsdemux creates a new pad (dynamic linking)"""
        pad_name = pad.get_name()
        pad_caps = pad.get_current_caps()

        if pad_caps:
            caps_str = pad_caps.to_string()
            logger.info(f"tsdemux pad-added: {pad_name}, caps: {caps_str}")

            # Link video pads to videoqueue
            if 'video' in caps_str:
                videoqueue = self.pipeline.get_by_name('videoqueue')
                if videoqueue:
                    sink_pad = videoqueue.get_static_pad('sink')
                    if sink_pad and not sink_pad.is_linked():
                        result = pad.link(sink_pad)
                        if result == Gst.PadLinkReturn.OK:
                            logger.info(f"Successfully linked {pad_name} to videoqueue")
                        else:
                            logger.error(f"Failed to link {pad_name} to videoqueue: {result}")
                    else:
                        logger.warning(f"videoqueue sink pad already linked or not found")
                else:
                    logger.error("videoqueue element not found in pipeline")
            # Ignore audio pads (we're doing video-only for low latency)
            elif 'audio' in caps_str:
                logger.info(f"Ignoring audio pad: {pad_name} (video-only mode)")
        else:
            logger.warning(f"tsdemux pad-added: {pad_name}, but caps are None")

    def _on_bus_error(self, bus, message):
        """Handle GStreamer bus error messages"""
        err, debug_info = message.parse_error()
        logger.error(f"GStreamer error from {message.src.get_name()}: {err.message}")
        if debug_info:
            logger.error(f"Debug info: {debug_info}")
        logger.error(f"Error domain: {err.domain}, code: {err.code}")

        if self.stream_active:
            logger.info("=== STREAM DISCONNECTED: Pipeline error ===")
            self.stream_active = False

        # Try to recover from error by resetting pipeline
        # Only quit loop for fatal errors
        error_is_fatal = "resource" in err.message.lower() or "not found" in err.message.lower()

        if error_is_fatal:
            logger.error("Fatal error detected, restarting pipeline...")
            if self.loop:
                self.loop.quit()
        else:
            logger.warning("Non-fatal error, attempting to reset pipeline...")
            if self.pipeline:
                self.pipeline.set_state(Gst.State.READY)
                time.sleep(1)
                self.pipeline.set_state(Gst.State.PLAYING)

    def _on_bus_eos(self, bus, message):
        """Handle end-of-stream messages"""
        if self.stream_active:
            logger.info("=== STREAM DISCONNECTED: End of stream ===")
            self.stream_active = False

        self.video_frame_count = 0
        self.audio_frame_count = 0

        # tcpserversrc can only accept ONE connection in its lifetime
        # We need to recreate the entire pipeline for reconnection
        logger.info("Restarting pipeline for next connection...")

        # Clear ready flag BEFORE quitting to prevent FFmpeg from restarting too early
        self.pipeline_ready.clear()

        if self.loop:
            self.loop.quit()  # This will trigger pipeline recreation

    def _on_bus_warning(self, bus, message):
        """Handle GStreamer warning messages"""
        warn, debug_info = message.parse_warning()
        logger.warning(f"GStreamer warning from {message.src.get_name()}: {warn.message}")

    def _on_state_changed(self, bus, message):
        """Handle state change messages"""
        if message.src == self.pipeline:
            old_state, new_state, pending_state = message.parse_state_changed()
            logger.info(f"Pipeline state changed: {old_state.value_nick} -> {new_state.value_nick}")
        elif 'tcpserversrc' in message.src.get_name():
            old_state, new_state, pending_state = message.parse_state_changed()
            logger.info(f"tcpserversrc state changed: {old_state.value_nick} -> {new_state.value_nick}")

    def _on_async_done(self, bus, message):
        """Handle async-done messages"""
        logger.info(f"ASYNC-DONE received from {message.src.get_name()}")
        # Check if tcpserversrc has accepted a connection
        if 'tcpserversrc' in message.src.get_name():
            logger.info("tcpserversrc async operation complete - FFmpeg connected")

    def _on_bus_info(self, bus, message):
        """Handle info messages"""
        info, debug_info = message.parse_info()
        logger.info(f"GStreamer info from {message.src.get_name()}: {info.message}")

    def stop(self):
        """Stop the UDP receiver pipeline"""
        self.running = False
        if self.loop:
            self.loop.quit()
        self._cleanup()
        logger.info("GStreamer UDP receiver stopped")

    def _cleanup(self):
        """Clean up resources"""
        if self.pipeline:
            # Remove bus watch
            bus = self.pipeline.get_bus()
            if bus:
                bus.remove_signal_watch()
            # Stop pipeline
            self.pipeline.set_state(Gst.State.NULL)
            self.pipeline = None


class ZeroconfService:
    """mDNS/Zeroconf service for gateway discovery"""

    def __init__(self, port: int, name: str = "HStreamer Gateway"):
        self.port = port
        self.name = name
        self.zeroconf = None
        self.info = None

    def start(self):
        """Register service"""
        try:
            # Get local IP
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            local_ip = s.getsockname()[0]
            s.close()

            # Create service info
            self.info = ServiceInfo(
                "_hstreamer._tcp.local.",
                f"{self.name}._hstreamer._tcp.local.",
                addresses=[socket.inet_aton(local_ip)],
                port=self.port,
                properties={
                    'version': '2.0',
                    'protocol': 'rtmp'
                },
                server=f"{socket.gethostname()}.local."
            )

            self.zeroconf = Zeroconf()
            self.zeroconf.register_service(self.info)

            logger.info(f"mDNS service registered: {self.name} at {local_ip}:{self.port}")

        except Exception as e:
            logger.error(f"Failed to register mDNS service: {e}")

    def stop(self):
        """Unregister service"""
        try:
            if self.zeroconf and self.info:
                self.zeroconf.unregister_service(self.info)
                self.zeroconf.close()
                logger.info("mDNS service unregistered")
        except Exception as e:
            logger.error(f"Failed to unregister mDNS service: {e}")


class WebServer:
    """HTTP and WebSocket server to serve web client and forward frames"""

    def __init__(self, host: str, http_port: int, frame_queue: Queue, audio_queue: Queue = None, video_mode: str = 'h264', stream_receiver=None):
        self.host = host
        self.http_port = http_port
        self.frame_queue = frame_queue
        self.audio_queue = audio_queue  # Optional - None for video-only mode
        self.video_mode = video_mode  # 'h264' or 'jpeg'
        self.stream_receiver = stream_receiver  # Reference to GStreamerUdpReceiver for SPS/PPS access
        self.clients: Set[web.WebSocketResponse] = set()
        self.running = False
        self.frame_count = 0
        self.app = None
        self.runner = None

    async def websocket_handler(self, request):
        """Handle WebSocket connections"""
        ws = web.WebSocketResponse()
        await ws.prepare(request)

        client_id = f"{request.remote}:{request.transport.get_extra_info('peername')[1]}"
        logger.info(f"Web client connected: {client_id}")

        self.clients.add(ws)

        try:
            # Send initial connection message
            await ws.send_str(json.dumps({
                'type': 'connected',
                'message': 'Connected to HStreamer gateway'
            }))

            # Send codec information (include SPS/PPS for H.264 mode if available)
            # Audio removed for low latency - use Bluetooth for audio
            codec_info = {
                'type': 'codec-info',
                'video': self.video_mode,
                'audio': 'none'  # Audio disabled
            }

            # Include SPS/PPS for WebCodecs H.264 decoder
            if self.video_mode == 'h264' and self.stream_receiver:
                if self.stream_receiver.sps and self.stream_receiver.pps:
                    # SPS/PPS are already base64-encoded strings from the parser
                    codec_info['sps'] = self.stream_receiver.sps
                    codec_info['pps'] = self.stream_receiver.pps
                    logger.info("Sending SPS/PPS to web client for H.264 decoding")

            await ws.send_str(json.dumps(codec_info))

            # Keep connection alive and handle incoming messages
            async for msg in ws:
                if msg.type == web.WSMsgType.TEXT:
                    try:
                        data = json.loads(msg.data)
                        if data.get('type') == 'ping':
                            await ws.send_str(json.dumps({'type': 'pong'}))
                    except json.JSONDecodeError:
                        pass
                elif msg.type == web.WSMsgType.ERROR:
                    logger.error(f"WebSocket error: {ws.exception()}")

        except Exception as e:
            logger.error(f"WebSocket handler error: {e}")
        finally:
            self.clients.discard(ws)
            logger.info(f"Web client disconnected: {client_id}")

        return ws

    async def index_handler(self, request):
        """Serve the main HTML page"""
        web_client_dir = Path(__file__).parent.parent / 'web-client'
        index_file = web_client_dir / 'index.html'

        if not index_file.exists():
            return web.Response(text="Web client not found", status=404)

        return web.FileResponse(index_file)

    async def start(self):
        """Start the HTTP and WebSocket server"""
        self.running = True

        # Create aiohttp application
        self.app = web.Application()

        # Add routes
        self.app.router.add_get('/ws', self.websocket_handler)
        self.app.router.add_get('/', self.index_handler)

        # Serve static files (CSS, JS)
        web_client_dir = Path(__file__).parent.parent / 'web-client'
        if web_client_dir.exists():
            self.app.router.add_static('/', web_client_dir, name='static')

        # Start video and audio broadcasters
        asyncio.create_task(self._broadcast_frames())
        asyncio.create_task(self._broadcast_audio())

        # Start server
        self.runner = web.AppRunner(self.app)
        await self.runner.setup()
        site = web.TCPSite(self.runner, self.host, self.http_port)
        await site.start()

        logger.info(f"HTTP server started on http://{self.host}:{self.http_port}")
        logger.info(f"WebSocket available at ws://{self.host}:{self.http_port}/ws")
        logger.info(f"Web client available at http://{self.host}:{self.http_port}/")

    async def stop(self):
        """Stop the server"""
        self.running = False
        if self.runner:
            await self.runner.cleanup()

    def _is_key_frame(self, video_data):
        """Check if H.264 frame contains IDR NAL unit (type 5) indicating a key frame"""
        try:
            i = 0
            while i < len(video_data) - 4:
                # Look for start code (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
                if video_data[i:i+4] == b'\x00\x00\x00\x01':
                    nal_type = video_data[i+4] & 0x1F
                    if nal_type == 5:  # IDR NAL unit = key frame
                        return True
                    i += 4
                elif video_data[i:i+3] == b'\x00\x00\x01':
                    nal_type = video_data[i+3] & 0x1F
                    if nal_type == 5:  # IDR NAL unit = key frame
                        return True
                    i += 3
                else:
                    i += 1
            return False
        except Exception as e:
            logger.warning(f"Error detecting key frame: {e}")
            return False

    async def _broadcast_frames(self):
        """Broadcast video frames to all connected clients"""
        logger.info("Video frame broadcaster started")
        last_log_time = time.time()

        while self.running:
            try:
                # Get frame from queue (non-blocking)
                if not self.frame_queue.empty():
                    video_data = self.frame_queue.get_nowait()

                    # Encode frame as base64
                    frame_b64 = base64.b64encode(video_data).decode('utf-8')

                    # Create message based on video mode
                    if self.video_mode == 'h264':
                        # Detect if this is a key frame (contains IDR NAL unit, type 5)
                        is_key_frame = self._is_key_frame(video_data)

                        message = json.dumps({
                            'type': 'video-frame-h264',
                            'data': frame_b64,
                            'keyFrame': is_key_frame
                        })
                    else:
                        message = json.dumps({
                            'type': 'video-frame',
                            'data': frame_b64,
                            'format': 'jpeg'
                        })

                    # Broadcast to all clients
                    if self.clients:
                        self.frame_count += 1

                        # Log FPS every 5 seconds
                        current_time = time.time()
                        if current_time - last_log_time >= 5.0:
                            fps = self.frame_count / (current_time - last_log_time)
                            logger.info(f"Broadcasting at ~{fps:.1f} FPS to {len(self.clients)} client(s)")
                            self.frame_count = 0
                            last_log_time = current_time

                        disconnected = set()
                        for client in self.clients:
                            try:
                                if not client.closed:
                                    await client.send_str(message)
                            except Exception as e:
                                logger.warning(f"Error sending video to client: {e}")
                                disconnected.add(client)

                        # Remove disconnected clients
                        self.clients -= disconnected

                # Small delay to prevent busy-waiting
                await asyncio.sleep(0.01)

            except Exception as e:
                logger.error(f"Error in video broadcaster: {e}")
                await asyncio.sleep(0.1)

    async def _broadcast_audio(self):
        """Broadcast audio packets to all connected clients"""
        logger.info("Audio broadcaster started")

        while self.running:
            try:
                # Get audio packet from queue (non-blocking) - skip if audio disabled
                if self.audio_queue and not self.audio_queue.empty():
                    opus_data = self.audio_queue.get_nowait()

                    # Encode packet as base64
                    audio_b64 = base64.b64encode(opus_data).decode('utf-8')

                    # Create message
                    message = json.dumps({
                        'type': 'audio-frame',
                        'data': audio_b64,
                        'format': 'opus'
                    })

                    # Broadcast to all clients
                    if self.clients:
                        disconnected = set()
                        for client in self.clients:
                            try:
                                if not client.closed:
                                    await client.send_str(message)
                            except Exception as e:
                                logger.warning(f"Error sending audio to client: {e}")
                                disconnected.add(client)

                        # Remove disconnected clients
                        self.clients -= disconnected

                # Small delay to prevent busy-waiting
                await asyncio.sleep(0.01)

            except Exception as e:
                logger.error(f"Error in audio broadcaster: {e}")
                await asyncio.sleep(0.1)


class Gateway:
    """Main gateway coordinator"""

    def __init__(
        self,
        udp_port: int = 5000,
        http_host: str = '0.0.0.0',
        http_port: int = 8765,
        service_name: str = "HStreamer Gateway",
        video_mode: str = 'h264'
    ):
        self.udp_port = udp_port
        self.http_host = http_host
        self.http_port = http_port
        self.service_name = service_name
        self.video_mode = video_mode

        self.frame_queue = Queue(maxsize=100)  # Larger queue for burst handling
        # Audio removed for low latency - use Bluetooth for audio

        # Use pure Python MPEG-TS parser for H.264 mode (no GStreamer overhead!)
        # Use GStreamer only for JPEG mode (needs decoding)
        if video_mode == 'h264':
            self.stream_receiver = MpegTsReceiver(udp_port, self.frame_queue, transport='tcp')
            logger.info("Using pure Python MPEG-TS parser with TCP transport (no GStreamer)")
        else:
            self.stream_receiver = GStreamerUdpReceiver(udp_port, self.frame_queue, None, video_mode)
            logger.info("Using GStreamer for JPEG mode")

        self.web_server = WebServer(http_host, http_port, self.frame_queue, None, video_mode, self.stream_receiver)
#        self.zeroconf_service = ZeroconfService(udp_port, service_name)

    async def start_async(self):
        """Start the gateway (async)"""
        logger.info("=" * 60)
        logger.info("HStreamer Gateway v3 - UDP Direct Streaming")
        logger.info("=" * 60)
        logger.info(f"UDP Server: udp://<gateway-ip>:{self.udp_port}")
        logger.info(f"  Android app sends MPEG-TS stream here (mDNS auto-discovery)")
        logger.info(f"HTTP Server: http://{self.http_host}:{self.http_port}")
        logger.info(f"WebSocket: ws://{self.http_host}:{self.http_port}/ws")
        logger.info(f"Service Name: {self.service_name}")
        logger.info(f"Video Mode: {self.video_mode.upper()} ({'browser-side decode' if self.video_mode == 'h264' else 'gateway-side decode'})")
        logger.info("Features: Video-only streaming (audio via Bluetooth), Ultra-low latency UDP transport")
        logger.info("=" * 60)

        # Start mDNS advertisement
#        self.zeroconf_service.start()

        # Start UDP receiver
        self.stream_receiver.start()

        # Start web server (blocking)
        await self.web_server.start()

        # Keep running
        await asyncio.Future()

    def start(self):
        """Start the gateway (sync wrapper)"""
        asyncio.run(self.start_async())

    async def stop_async(self):
        """Stop the gateway (async)"""
        logger.info("Stopping HStreamer Gateway")
        self.stream_receiver.stop()
        await self.web_server.stop()
#        self.zeroconf_service.stop()

    def stop(self):
        """Stop the gateway (sync wrapper)"""
        asyncio.run(self.stop_async())


def main():
    """Main entry point"""
    import argparse

    parser = argparse.ArgumentParser(description='HStreamer Gateway v3 - UDP Direct Streaming with Integrated HTTP/WebSocket Server')
    parser.add_argument(
        '--udp-port',
        type=int,
        default=5000,
        help='UDP port for receiving MPEG-TS stream from Android (default: 5000)'
    )
    parser.add_argument(
        '--http-host',
        type=str,
        default='0.0.0.0',
        help='HTTP/WebSocket server host (default: 0.0.0.0)'
    )
    parser.add_argument(
        '--http-port',
        type=int,
        default=8765,
        help='HTTP/WebSocket server port (default: 8765)'
    )
    parser.add_argument(
        '--name',
        type=str,
        default='HStreamer Gateway',
        help='Service name for discovery (default: HStreamer Gateway)'
    )
    parser.add_argument(
        '--video-mode',
        type=str,
        choices=['h264', 'jpeg'],
        default='h264',
        help='Video encoding mode: h264 (browser-side decode, low CPU) or jpeg (gateway-side decode, legacy) (default: h264)'
    )

    args = parser.parse_args()

    gateway = Gateway(
        udp_port=args.udp_port,
        http_host=args.http_host,
        http_port=args.http_port,
        service_name=args.name,
        video_mode=args.video_mode
    )

    try:
        gateway.start()
    except KeyboardInterrupt:
        logger.info("Received interrupt signal")
        gateway.stop()


if __name__ == '__main__':
    main()
