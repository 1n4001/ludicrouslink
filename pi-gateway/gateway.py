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
from threading import Thread
from zeroconf import ServiceInfo, Zeroconf
import socket
from pathlib import Path
from aiohttp import web

gi.require_version('Gst', '1.0')
gi.require_version('GstApp', '1.0')
from gi.repository import Gst, GLib, GstApp

# Initialize GStreamer
Gst.init(None)

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class GStreamerRTMPReceiver:
    """GStreamer-based RTMP receiver that processes stream"""

    def __init__(self, port: int, frame_queue: Queue, audio_queue: Queue):
        self.port = port
        self.frame_queue = frame_queue
        self.audio_queue = audio_queue
        self.pipeline = None
        self.loop = None
        self.thread = None
        self.running = False
        self.retry_count = 0
        self.max_retries = 30  # Retry for 30 seconds

    def start(self):
        """Start the GStreamer pipeline in a separate thread"""
        self.running = True
        self.thread = Thread(target=self._run_pipeline, daemon=True)
        self.thread.start()
        logger.info("GStreamer RTMP receiver started")

    def _run_pipeline(self):
        """Run the GStreamer pipeline with retry logic"""

        rtmp_url = f"rtmp://127.0.0.1:{self.port}/live/stream"

        # Wait for stream to be available
        logger.info("Waiting for RTMP stream...")
        logger.info(f"Expecting stream at: {rtmp_url}")
        logger.info("Push stream from Android or test with:")
        logger.info(f"  ffmpeg -re -f lavfi -i testsrc -c:v libx264 -f flv {rtmp_url}")

        while self.running and self.retry_count < self.max_retries:
            try:
                pipeline_str = (
                    f"rtmpsrc location={rtmp_url} timeout=5 ! "
                    "flvdemux name=demux "
                    # Video branch
                    "demux.video ! queue ! h264parse ! avdec_h264 ! "
                    "videoconvert ! video/x-raw,format=RGB ! "
                    "videoscale ! video/x-raw,width=1280,height=720 ! "
                    "jpegenc quality=85 ! "
                    "appsink name=videosink emit-signals=true max-buffers=1 drop=true "
                    # Audio branch
                    "demux.audio ! queue ! aacparse ! avdec_aac ! "
                    "audioconvert ! audioresample ! "
                    "audio/x-raw,rate=48000,channels=2 ! "
                    "opusenc bitrate=128000 ! "
                    "appsink name=audiosink emit-signals=true max-buffers=1 drop=true"
                )

                logger.info(f"Attempt {self.retry_count + 1}/{self.max_retries}: Creating pipeline...")
                self.pipeline = Gst.parse_launch(pipeline_str)

                # Get the appsink elements
                videosink = self.pipeline.get_by_name('videosink')
                audiosink = self.pipeline.get_by_name('audiosink')

                videosink.connect('new-sample', self._on_new_video_sample)
                audiosink.connect('new-sample', self._on_new_audio_sample)

                # Create main loop
                self.loop = GLib.MainLoop()

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

                if ret == Gst.StateChangeReturn.SUCCESS or ret == Gst.StateChangeReturn.NO_PREROLL:
                    logger.info(f"Pipeline started successfully!")
                    logger.info("Waiting for video and audio from RTMP stream...")

                    # Run the loop
                    self.loop.run()
                    break  # Exit retry loop if successful
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

        if self.retry_count >= self.max_retries:
            logger.error("Max retries reached. Make sure:")
            logger.error("  1. nginx-rtmp is running: sudo systemctl status nginx")
            logger.error("  2. Port 1935 is listening: sudo netstat -tuln | grep 1935")
            logger.error("  3. Android is pushing stream OR test with ffmpeg")

    def _on_new_video_sample(self, appsink):
        """Callback for new video frame from GStreamer"""
        try:
            sample = appsink.emit('pull-sample')
            if sample:
                buffer = sample.get_buffer()

                # Extract buffer data
                success, map_info = buffer.map(Gst.MapFlags.READ)
                if success:
                    jpeg_data = bytes(map_info.data)
                    buffer.unmap(map_info)

                    # Put frame in queue (non-blocking)
                    if self.frame_queue.qsize() < 10:
                        self.frame_queue.put(jpeg_data)

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

                    # Put audio packet in queue (non-blocking)
                    if self.audio_queue.qsize() < 10:
                        self.audio_queue.put(opus_data)

                return Gst.FlowReturn.OK
        except Exception as e:
            logger.error(f"Error processing audio sample: {e}")
            return Gst.FlowReturn.ERROR

    def stop(self):
        """Stop the pipeline"""
        self.running = False
        if self.loop:
            self.loop.quit()
        self._cleanup()
        logger.info("GStreamer RTMP receiver stopped")

    def _cleanup(self):
        """Clean up resources"""
        if self.pipeline:
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

    def __init__(self, host: str, http_port: int, frame_queue: Queue, audio_queue: Queue):
        self.host = host
        self.http_port = http_port
        self.frame_queue = frame_queue
        self.audio_queue = audio_queue
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

    async def _broadcast_frames(self):
        """Broadcast video frames to all connected clients"""
        logger.info("Video frame broadcaster started")
        last_log_time = time.time()

        while self.running:
            try:
                # Get frame from queue (non-blocking)
                if not self.frame_queue.empty():
                    jpeg_data = self.frame_queue.get_nowait()

                    # Encode frame as base64
                    frame_b64 = base64.b64encode(jpeg_data).decode('utf-8')

                    # Create message
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
                # Get audio packet from queue (non-blocking)
                if not self.audio_queue.empty():
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
        rtmp_port: int = 1935,
        http_host: str = '0.0.0.0',
        http_port: int = 8765,
        service_name: str = "HStreamer Gateway"
    ):
        self.rtmp_port = rtmp_port
        self.http_host = http_host
        self.http_port = http_port
        self.service_name = service_name

        self.frame_queue = Queue(maxsize=10)
        self.audio_queue = Queue(maxsize=10)
        self.rtmp_receiver = GStreamerRTMPReceiver(rtmp_port, self.frame_queue, self.audio_queue)
        self.web_server = WebServer(http_host, http_port, self.frame_queue, self.audio_queue)
        self.zeroconf_service = ZeroconfService(rtmp_port, service_name)

    async def start_async(self):
        """Start the gateway (async)"""
        logger.info("=" * 60)
        logger.info("HStreamer Gateway v2 - Starting")
        logger.info("=" * 60)
        logger.info(f"RTMP Server Port: {self.rtmp_port}")
        logger.info(f"HTTP Server: http://{self.http_host}:{self.http_port}")
        logger.info(f"WebSocket: ws://{self.http_host}:{self.http_port}/ws")
        logger.info(f"Service Name: {self.service_name}")
        logger.info("Features: Video + Audio streaming, Web UI")
        logger.info("=" * 60)

        # Start mDNS advertisement
        self.zeroconf_service.start()

        # Start RTMP receiver
        self.rtmp_receiver.start()

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
        self.rtmp_receiver.stop()
        await self.web_server.stop()
        self.zeroconf_service.stop()

    def stop(self):
        """Stop the gateway (sync wrapper)"""
        asyncio.run(self.stop_async())


def main():
    """Main entry point"""
    import argparse

    parser = argparse.ArgumentParser(description='HStreamer Gateway v2 - Integrated HTTP/WebSocket Server')
    parser.add_argument(
        '--rtmp-port',
        type=int,
        default=1935,
        help='RTMP server port (default: 1935)'
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

    args = parser.parse_args()

    gateway = Gateway(
        rtmp_port=args.rtmp_port,
        http_host=args.http_host,
        http_port=args.http_port,
        service_name=args.name
    )

    try:
        gateway.start()
    except KeyboardInterrupt:
        logger.info("Received interrupt signal")
        gateway.stop()


if __name__ == '__main__':
    main()
