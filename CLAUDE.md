# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

HStreamer is a low-latency screen and audio streaming system that streams from Android devices to web browsers via a Raspberry Pi gateway. The system uses RTMP push model with mDNS auto-discovery.

**Architecture:** Android → RTMP → Gateway (nginx-rtmp + GStreamer + Python) → WebSocket → Web Browser

**Current version:** v2 (RTMP push model with audio support)

## Build and Run Commands

### Gateway (Raspberry Pi)

```bash
# Install nginx-rtmp (one-time setup)
cd pi-gateway
chmod +x install_nginx_rtmp.sh
./install_nginx_rtmp.sh

# Install Python dependencies
pip3 install -r requirements.txt

# Start gateway with defaults (port 8765)
python3 gateway.py

# Start gateway with custom settings
python3 gateway.py --http-port 9000 --rtmp-port 1935 --http-host 0.0.0.0

# Test nginx-rtmp status
curl http://localhost:8080/stat
```

### Android App

```bash
cd android-app

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install to connected device
./gradlew installDebug
# OR
adb install app/build/outputs/apk/debug/app-debug.apk

# View logs
adb logcat | grep HStreamer
```

### Testing Without Android

```bash
# Generate test video with audio (10 seconds)
ffmpeg -f lavfi -i testsrc=duration=10:size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=1000:duration=10 \
       -c:v libx264 -c:a aac test.mp4

# Stream test video on loop to gateway
ffmpeg -re -stream_loop -1 -i test.mp4 \
       -c:v copy -c:a copy \
       -f flv rtmp://localhost:1935/live/stream
```

## System Architecture

### Three-Component System

1. **Android App** (Kotlin) - `android-app/`
   - Captures screen via MediaProjection API
   - Captures internal audio via AudioPlaybackCapture (Android 10+)
   - Encodes to H.264 video + AAC audio
   - Discovers gateway via Android NSD (mDNS)
   - Pushes RTMP stream to gateway

2. **Gateway Server** (Python + GStreamer) - `pi-gateway/`
   - Receives RTMP via nginx-rtmp (port 1935)
   - Processes with GStreamer: H.264→JPEG + AAC→Opus
   - Serves web client via integrated aiohttp HTTP server (port 8765)
   - Streams frames via WebSocket on same port (/ws endpoint)
   - Advertises via mDNS using zeroconf

3. **Web Client** (Vanilla JS) - `web-client/`
   - Connects via WebSocket to gateway
   - Renders video frames to Canvas
   - Decodes Opus audio and plays via Web Audio API
   - Auto-detects WebSocket URL when served from gateway

### Data Flow

```
Android Device
  ↓ MediaProjection (screen) + AudioPlaybackCapture (audio)
  ↓ H.264 encoding + AAC encoding
  ↓ RTMP push (port 1935)
nginx-rtmp (localhost:1935)
  ↓ RTMP stream
GStreamer Pipeline
  ├─ Video: H.264 decode → RGB convert → scale (720p) → JPEG encode
  └─ Audio: AAC decode → resample (48kHz stereo) → Opus encode
Python Gateway (frame_queue + audio_queue)
  ↓ WebSocket (port 8765/ws)
  ↓ JSON messages: {type: "video-frame", data: base64} + {type: "audio-frame", data: base64}
Web Browser
  ├─ Canvas rendering (JPEG frames)
  └─ Web Audio API (Opus decoding + playback)
```

### Key Integration Points

**Gateway.py integrations:**
- `GStreamerRTMPReceiver`: Connects to `rtmp://127.0.0.1:1935/live/stream` and processes via GStreamer
- `WebServer` (aiohttp): Serves HTTP + WebSocket on single port, routes: `/` (index), `/ws` (WebSocket), static files
- `MdnsService` (zeroconf): Advertises `_hstreamer._tcp.local.` service
- Frame queues: `frame_queue` (video) and `audio_queue` (audio) bridge GStreamer → WebSocket

**Android integrations:**
- `ServiceDiscovery`: Uses Android NSD to find `_hstreamer._tcp` services
- `RtmpStreamer`: Wraps `rtmp-rtsp-stream-client-java` library for RTMP streaming
- `StreamingService`: Foreground service manages MediaProjection and RtmpStreamer lifecycle

**Web client integrations:**
- WebSocket message handler routes `type: "video-frame"` to Canvas, `type: "audio-frame"` to Opus decoder
- `opus-decoder` library (local file: `opus-decoder.min.js`) decodes Opus → PCM for Web Audio API

## File Consolidation Policy

**IMPORTANT:** The gateway server must be maintained as a single `gateway.py` file. Do NOT create multiple versions like `gateway_v2.py`, `gateway_v2_fixed.py`, etc. All changes go directly into `pi-gateway/gateway.py`.

## Port Configuration

- **Default HTTP/WebSocket port:** 8765 (configurable via `--http-port`)
- **Default RTMP port:** 1935 (configurable via `--rtmp-port`)
- **nginx stats:** 8080 (http://gateway-ip:8080/stat)
- **mDNS:** UDP 5353 (automatic discovery)

Port 8765 was chosen as default to avoid conflicts with common development tools on port 8080.

## GStreamer Pipeline Details

The gateway uses a complex GStreamer pipeline that:
- Connects to RTMP via `rtmpsrc`
- Demuxes with `flvdemux` into separate video and audio branches
- Video: `h264parse → avdec_h264 → videoconvert → videoscale → jpegenc → appsink`
- Audio: `aacparse → avdec_aac → audioconvert → audioresample → opusenc → appsink`
- Both appsinks emit signals captured by Python callbacks

**Critical:** GStreamer runs in a separate thread with its own GLib.MainLoop. Frame/audio data flows through thread-safe queues to the asyncio WebSocket broadcaster.

## Audio Implementation

Audio requires the full pipeline:
1. Android captures with AudioPlaybackCapture (requires Android 10+, API 29+)
2. Encodes to AAC in RTMP stream
3. Gateway decodes AAC → resamples to 48kHz stereo → encodes to Opus
4. WebSocket sends base64-encoded Opus packets with `type: "audio-frame"`
5. Web client decodes Opus using `opus-decoder` library
6. Web Audio API plays PCM audio

**Browser autoplay policy:** User must interact with page before AudioContext can start.

## Documentation

All documentation is in `docs/` directory with lowercase-hyphen naming:
- `docs/quick-start.md` - Primary getting started guide
- `docs/integrated-web-server.md` - HTTP server architecture
- `docs/audio-implementation.md` - Audio streaming details
- `docs/test-streaming.md` - FFmpeg test commands
- `docs/troubleshooting.md` - Common issues

Update documentation when making architectural changes, especially port configurations or data flow modifications.

## Common Development Patterns

### Adding New WebSocket Message Types

1. Define message type in gateway.py WebServer._broadcast_frames() or _broadcast_audio()
2. Send as JSON: `json.dumps({'type': 'new-type', 'data': base64_data})`
3. Handle in client.js onMessage() switch statement
4. Update docs/api.md with message format

### Modifying GStreamer Pipeline

1. Update pipeline string in GStreamerRTMPReceiver._run_pipeline()
2. Test pipeline independently: `gst-launch-1.0 [pipeline]`
3. Ensure appsink callbacks are connected
4. Handle pipeline state changes (NULL → PLAYING)
5. Add retry logic for connection failures

### Android RTMP URL Changes

If modifying RTMP endpoint:
1. Update RtmpStreamer.kt URL construction
2. Update gateway.py GStreamer rtmpsrc location
3. Update nginx.conf application name if needed
4. Update all documentation with new URL format

## Environment-Specific Notes

**Raspberry Pi:**
- GStreamer hardware acceleration: Use `omxh264dec` on Pi 3, `v4l2h264dec` on Pi 4+
- CPU usage: 30-50% on Pi 4 with software decoding
- Memory: Minimum 1GB RAM recommended

**Android:**
- minSdk 29 (Android 10) required for AudioPlaybackCapture
- targetSdk 34 (Android 14)
- Requires RECORD_AUDIO and screen capture permissions

**Web Browsers:**
- Chrome/Edge 90+, Firefox 88+, Safari 14+
- Requires WebSocket and Web Audio API support
- Opus decoder: Local file `web-client/opus-decoder.min.js` (v0.7.7)
