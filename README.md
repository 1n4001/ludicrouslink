# HStreamer - Low-Latency Android Screen & Audio Streaming

A complete system for real-time screen and audio streaming from Android devices to web browsers over a local network.

## Quick Start

**3 simple steps to start streaming:**

1. **Start Gateway** (on Raspberry Pi or Linux machine)
   ```bash
   cd pi-gateway
   python3 gateway.py
   ```

2. **Start Streaming** (on Android)
   - Open HStreamer app
   - Select gateway from dropdown
   - Tap "Start Streaming"

3. **View Stream** (in web browser)
   - Open http://gateway-ip:8765/
   - Click "Connect"

That's it! See [Quick Start Guide](docs/quick-start.md) for detailed instructions.

## System Architecture (v2)

```
┌─────────────────┐         ┌──────────────────┐         ┌──────────────┐
│  Android Device │         │  Gateway (Pi)    │         │  Web Browser │
│                 │         │                  │         │              │
│  Screen Capture ├────────►│  nginx-rtmp      │         │              │
│  Audio Capture  │  RTMP   │       ↓          │         │              │
│  (H.264/AAC)    │  Push   │  GStreamer       │         │              │
│                 │         │  (decode/encode) │         │              │
│                 │         │       ↓          │         │              │
│                 │         │  HTTP Server     ├────────►│  Canvas      │
│                 │         │  + WebSocket     │ HTTP/WS │  Web Audio   │
│                 │         │  (video+audio)   │         │              │
│                 │◄────────┤  mDNS Service    │         │              │
│  Auto-discover  │  mDNS   │  (discovery)     │         │              │
└─────────────────┘         └──────────────────┘         └──────────────┘
```

### How It Works

1. **Android App** captures screen (H.264) and internal audio (AAC)
2. **Android App** automatically discovers gateway via mDNS
3. **Android App** pushes RTMP stream to gateway
4. **Gateway** receives stream via nginx-rtmp
5. **Gateway** processes with GStreamer:
   - Decodes H.264 video → encodes to JPEG
   - Decodes AAC audio → encodes to Opus
6. **Gateway** serves web client and streams via WebSocket
7. **Web Browser** displays video on canvas and plays audio

## Features

- **Low Latency** - 100-200ms end-to-end
- **Video Streaming** - 720p @ 30fps, H.264 encoding
- **Audio Streaming** - Internal audio capture, Opus codec
- **Auto-Discovery** - No manual IP configuration needed
- **Integrated Web Server** - Single unified gateway
- **Multiple Viewers** - Multiple browsers can view simultaneously
- **Modern Web UI** - Fullscreen, screenshots, FPS/latency stats

## Documentation

📖 **[Complete Documentation →](docs/)**

### Essential Guides

- **[Quick Start Guide](docs/quick-start.md)** - Get running in 3 steps
- **[Integrated Web Server](docs/integrated-web-server.md)** - How the unified server works
- **[Audio Implementation](docs/audio-implementation.md)** - Audio streaming details
- **[Test Streaming](docs/test-streaming.md)** - Test with FFmpeg (no Android needed)
- **[Troubleshooting](docs/troubleshooting.md)** - Common issues and solutions

### Configuration & Setup

- **[Network Setup](docs/network-setup.md)** - Network and firewall configuration
- **[Install as Service](docs/install-as-service.md)** - Auto-start gateway on boot
- **[Port Configuration](docs/port-change-summary.md)** - Customize ports

### Reference

- **[API Reference](docs/api.md)** - WebSocket message formats
- **[Quick Reference](docs/quick-reference.md)** - Commands and tips
- **[Project Structure](docs/project-structure.md)** - Codebase organization

### Migration

- **[v1 to v2 Migration](docs/migration-v1-to-v2.md)** - Upgrade from v1 (RTSP)

## Requirements

### Raspberry Pi Gateway
- Raspberry Pi 3/4/5 (Pi 4+ recommended)
- Raspbian/Debian Linux
- Python 3.7+
- GStreamer 1.14+
- nginx with RTMP module

### Android Device
- Android 10+ (API 29+)
- Internal audio requires Android 10+
- Same network as gateway

### Web Browser
- Chrome/Edge 90+
- Firefox 88+
- Safari 14+

## Installation

### 1. Gateway Setup

```bash
# Install nginx-rtmp
cd pi-gateway
chmod +x install_nginx_rtmp.sh
./install_nginx_rtmp.sh

# Install Python dependencies
pip3 install -r requirements.txt

# Start gateway
python3 gateway.py
```

See [Quick Start Guide](docs/quick-start.md) for detailed instructions.

### 2. Android App

```bash
# Build with Android Studio or Gradle
cd android-app
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. Web Client

The web client is automatically served by the gateway at:
```
http://gateway-ip:8765/
```

No separate installation needed!

## Usage

### Start Gateway
```bash
python3 gateway.py
```

**With custom settings:**
```bash
python3 gateway.py --http-port 9000 --rtmp-port 1935
```

### Test Without Android

Use FFmpeg to generate and stream test video:

```bash
# Generate 10s test video
ffmpeg -f lavfi -i testsrc=duration=10:size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=1000:duration=10 \
       -c:v libx264 -c:a aac test.mp4

# Stream on loop
ffmpeg -re -stream_loop -1 -i test.mp4 \
       -c:v copy -c:a copy \
       -f flv rtmp://localhost:1935/live/stream
```

See [Test Streaming Guide](docs/test-streaming.md) for more options.

## Project Structure

```
hstreamer/
├── android-app/          # Android application (Kotlin)
│   ├── app/src/main/
│   │   ├── java/com/cesicorp/hstreamer/
│   │   │   ├── MainActivity.kt
│   │   │   ├── StreamingService.kt
│   │   │   ├── RtmpStreamer.kt
│   │   │   └── ServiceDiscovery.kt
│   │   └── res/
│   └── build.gradle
│
├── pi-gateway/           # Gateway server (Python)
│   ├── gateway.py        # Main server (HTTP + WebSocket + RTMP processing)
│   ├── requirements.txt  # Python dependencies
│   ├── nginx.conf        # nginx-rtmp configuration
│   └── install_nginx_rtmp.sh
│
├── web-client/           # Web viewer (HTML/JS)
│   ├── index.html        # Main page (served by gateway)
│   ├── client.js         # WebSocket client + A/V handling
│   └── style.css
│
└── docs/                 # Documentation
    ├── README.md         # Documentation index
    ├── quick-start.md    # Getting started guide
    ├── audio-implementation.md
    ├── integrated-web-server.md
    ├── test-streaming.md
    └── ... (see docs/README.md)
```

## Technology Stack

**Android:**
- Kotlin, MediaProjection API, AudioPlaybackCapture
- rtmp-rtsp-stream-client-java (RTMP client)
- Android NSD (mDNS discovery)

**Gateway:**
- Python 3 (asyncio), aiohttp, websockets
- GStreamer (video/audio processing)
- nginx-rtmp (RTMP server)
- zeroconf (mDNS advertisement)

**Web Client:**
- Vanilla JavaScript
- WebSocket, Canvas API, Web Audio API
- opus-decoder (audio decoding)

## Performance

| Metric | Typical Value |
|--------|--------------|
| Video Latency | 100-200ms |
| Audio Latency | 40-90ms |
| Resolution | 720p @ 30fps |
| Video Bitrate | 2 Mbps |
| Audio Quality | 48kHz stereo, Opus 128kbps |
| CPU Usage (Pi 4) | 30-50% |

## URLs and Ports

### Default Configuration
- **Web Client:** http://gateway-ip:8765/
- **WebSocket:** ws://gateway-ip:8765/ws
- **RTMP Server:** rtmp://gateway-ip:1935/live/stream
- **nginx Stats:** http://gateway-ip:8080/stat

All ports are configurable via command-line arguments.

## Troubleshooting

**Gateway not discovered:**
- Check devices on same network
- Verify mDNS not blocked (port 5353 UDP)
- Check gateway shows "mDNS service registered"

**No video in browser:**
- Check WebSocket connection status
- Verify gateway logs show "Pipeline started"
- Check firewall allows port 8765

**No audio:**
- Click anywhere on page (browser autoplay policy)
- Check browser console for "Audio system initialized"
- Verify AudioContext state is "running"

See [Troubleshooting Guide](docs/troubleshooting.md) for detailed solutions.

## Version History

### Current
- ✅ RTMP push model (Android → Gateway)
- ✅ Automatic gateway discovery (mDNS)
- ✅ Audio streaming (Opus codec)
- ✅ Integrated HTTP server
- ✅ Unified port for HTTP + WebSocket

## Contributing

Contributions welcome! This project includes:
- Android app development (Kotlin)
- Gateway server (Python, GStreamer)
- Web client (JavaScript, WebSocket)
- Documentation improvements

## License

[Your license here]

## Support

- 📖 Read the [docs](docs/)
- 🐛 Check [Troubleshooting](docs/troubleshooting.md)
- ❓ Review [Quick Start](docs/quick-start.md)

---

**Ready to start?** → [Quick Start Guide](docs/quick-start.md)
