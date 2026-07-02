# LudicrousLink Documentation

Complete documentation for LudicrousLink - a low-latency screen and audio streaming system for Android to web browsers.

## Quick Links

- **New Users?** Start with [Quick Start Guide](quick-start.md)
- **Migrating from v1?** See [Migration Guide](migration-v1-to-v2.md)
- **Having Issues?** Check [Troubleshooting](troubleshooting.md)

## Documentation Index

### Getting Started

- **[quick-start.md](quick-start.md)** - Complete guide to get LudicrousLink running in 3 steps (v2, recommended)
- **[quickstart-v1.md](quickstart-v1.md)** - Quick start guide for v1 (RTSP pull model, legacy)
- **[readme-v2.md](readme-v2.md)** - Comprehensive v2 documentation with architecture details

### Core Features

- **[video-architecture.md](video-architecture.md)** - Video pipeline architecture: H.264 passthrough vs JPEG modes (NEW)
- **[audio-implementation.md](audio-implementation.md)** - How audio streaming works (AAC → Opus → Web Audio API)
- **[integrated-web-server.md](integrated-web-server.md)** - Built-in HTTP server that hosts the web client
- **[test-streaming.md](test-streaming.md)** - Test the gateway using FFmpeg instead of Android

### Configuration & Setup

- **[network-setup.md](network-setup.md)** - Network configuration and mDNS discovery setup
- **[install-as-service.md](install-as-service.md)** - Run gateway as a systemd service (auto-start)
- **[port-change-summary.md](port-change-summary.md)** - Default port changes (8080 → 8765)

### Reference

- **[api.md](api.md)** - WebSocket API reference and message formats
- **[quick-reference.md](quick-reference.md)** - Command reference and quick tips
- **[project-structure.md](project-structure.md)** - Codebase structure and file organization

### Migration & Troubleshooting

- **[migration-v1-to-v2.md](migration-v1-to-v2.md)** - How to migrate from v1 (RTSP) to v2 (RTMP)
- **[troubleshooting.md](troubleshooting.md)** - Common issues and solutions

## Architecture Overview

```
┌─────────────────┐         ┌──────────────────┐         ┌──────────────┐
│  Android Device │         │  Gateway (Pi)    │         │  Web Browser │
│                 │         │                  │         │              │
│  Screen Capture ├────────►│  nginx-rtmp      │         │              │
│  Audio Capture  │  RTMP   │       ↓          │         │              │
│  (H.264/AAC)    │         │  GStreamer       │         │              │
│                 │         │  (decode/encode) │         │              │
│                 │         │       ↓          │         │              │
│                 │         │  HTTP Server     ├────────►│  Canvas      │
│                 │         │  + WebSocket     │ HTTP/WS │  Web Audio   │
│                 │         │  (video+audio)   │         │              │
│                 │◄────────┤  mDNS Service    │         │              │
│  Auto-discover  │  mDNS   │  (discovery)     │         │              │
└─────────────────┘         └──────────────────┘         └──────────────┘
```

## Feature Comparison: v1 vs v2

| Feature | v1 (Legacy) | v2 (Current) |
|---------|-------------|--------------|
| Protocol | RTSP (pull) | RTMP (push) |
| Discovery | Manual IP | mDNS automatic |
| Audio | No | Yes (Opus codec) |
| Web Client | Separate server | Integrated |
| WebSocket | Separate port | Same port as HTTP |
| Gateway Setup | Know Android IP | Android discovers gateway |

**Recommendation:** Use v2 for all new deployments.

## Common Tasks

### Start Gateway (Default Settings)
```bash
cd pi-gateway
python3 gateway.py  # H.264 mode by default
```
Access at: http://gateway-ip:8765/

### Start Gateway (Custom Settings)
```bash
# Custom port
python3 gateway.py --http-port 9000

# JPEG fallback mode (for testing or legacy browsers)
python3 gateway.py --video-mode jpeg

# Full configuration
python3 gateway.py --http-port 8765 --video-mode h264
```

### Test Without Android
```bash
# Generate test video
ffmpeg -f lavfi -i testsrc=duration=10:size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=1000:duration=10 \
       -c:v libx264 -c:a aac test.mp4

# Stream on loop
ffmpeg -re -stream_loop -1 -i test.mp4 \
       -c:v copy -c:a copy \
       -f flv rtmp://localhost:1935/live/stream
```

### Install as Systemd Service
```bash
sudo cp docs/ludicrouslink.service /etc/systemd/system/
sudo systemctl enable ludicrouslink
sudo systemctl start ludicrouslink
```

## URLs and Ports

### Default Configuration

- **Web Client:** http://gateway-ip:8765/
- **WebSocket:** ws://gateway-ip:8765/ws
- **RTMP Server:** rtmp://gateway-ip:1935/live/stream
- **nginx Stats:** http://gateway-ip:8080/stat

### Customization

All ports are configurable via command-line arguments:

```bash
python3 gateway.py --rtmp-port 1935 --http-port 8765 --http-host 0.0.0.0
```

## System Requirements

### Raspberry Pi Gateway

**Hardware:**
- Raspberry Pi 3/4/5 (Pi 4+ recommended for best performance)
- 1GB+ RAM
- Network connection (Ethernet recommended)

**Software:**
- Raspbian OS (Debian-based Linux)
- Python 3.7+
- GStreamer 1.14+
- nginx with RTMP module
- Python packages: aiohttp, websockets, PyGObject, zeroconf

### Android Device

**Requirements:**
- Android 10+ (API 29+)
- Internal audio capture requires Android 10+
- Same network as gateway

### Web Browser (Viewer)

**Supported Browsers:**
- Chrome/Edge 94+ (recommended - supports H.264 hardware decode)
- Firefox 88+ (JPEG fallback mode)
- Safari 14+ (JPEG fallback mode)
- Any modern browser with WebSocket and Web Audio API support

## Performance Characteristics

### H.264 Passthrough Mode (Default, Chrome/Edge 94+)

| Metric | Value | Notes |
|--------|-------|-------|
| Video Latency | 80-150ms | Hardware-accelerated decode |
| Audio Latency | 40-90ms | Lower than video |
| Video Quality | Native resolution @ 30fps | No forced scaling |
| Video Bitrate | 1-2 Mbps | Android encoding bitrate |
| Audio Quality | 48kHz stereo | Opus @ 128kbps |
| CPU Usage (Pi 4) | **5-10%** | 85% reduction vs JPEG |
| Browser CPU | <1% | GPU hardware decode |
| Network Bandwidth | 1-2 Mbps | Per stream |

### JPEG Fallback Mode (Firefox, Safari)

| Metric | Value | Notes |
|--------|-------|-------|
| Video Latency | 100-200ms | Software decode + JPEG encode |
| Audio Latency | 40-90ms | Lower than video |
| Video Quality | 720p @ 30fps | Fixed scaling |
| Video Bitrate | 1-2 Mbps | JPEG compression |
| Audio Quality | 48kHz stereo | Opus @ 128kbps |
| CPU Usage (Pi 4) | 45-65% | GStreamer software processing |
| Browser CPU | 5-15% | JPEG decode |
| Network Bandwidth | 1-2 Mbps | Per stream |

## Support and Contribution

### Getting Help

1. Check [Troubleshooting Guide](troubleshooting.md)
2. Review [Quick Start Guide](quick-start.md)
3. Check gateway logs for errors
4. Verify network connectivity

### Common Issues

- **Gateway not discovered:** Check mDNS/firewall (port 5353 UDP)
- **No video in browser:** Check WebSocket connection and gateway logs
- **No audio:** Click page to resume AudioContext (browser autoplay policy)
- **Port conflicts:** Use `--http-port` to specify different port

### Log Files

**Gateway logs:**
```bash
python3 gateway.py  # All output to console
```

**nginx-rtmp logs:**
```bash
sudo tail -f /var/log/nginx/error.log
```

**Android logs:**
```bash
adb logcat | grep LudicrousLink
```

## Development

### Project Structure

```
ludicrouslink/
├── android-app/          # Android application (Kotlin)
├── pi-gateway/           # Raspberry Pi gateway (Python)
│   ├── gateway.py        # Main gateway server
│   ├── requirements.txt  # Python dependencies
│   └── nginx.conf        # nginx-rtmp configuration
├── web-client/           # Web browser client (HTML/JS)
│   ├── index.html        # Main page
│   ├── client.js         # WebSocket client + audio/video handling
│   └── style.css         # Styling
└── docs/                 # Documentation (you are here!)
```

See [project-structure.md](project-structure.md) for detailed file descriptions.

### Technology Stack

**Android:**
- Kotlin
- MediaProjection API (screen capture)
- AudioPlaybackCapture (internal audio)
- rtmp-rtsp-stream-client-java library
- Android NSD (service discovery)

**Gateway:**
- Python 3.7+ (asyncio)
- GStreamer (video/audio processing)
- aiohttp (HTTP server + WebSocket)
- zeroconf (mDNS service advertisement)
- nginx-rtmp (RTMP server)

**Web Client:**
- Vanilla JavaScript (no frameworks)
- WebSocket API
- Canvas API (video rendering)
- Web Audio API (audio playback)
- Opus decoder (opus-decoder library)

## License

See the main repository for license information.

## Version History

### v2 (Current)
- RTMP push model
- Automatic gateway discovery (mDNS)
- Audio streaming support
- Integrated web server
- Single unified port for HTTP + WebSocket

### v1 (Legacy)
- RTSP pull model
- Manual IP configuration
- Video only
- Separate web server required
- Separate WebSocket port

---

**Need help?** Start with the [Quick Start Guide](quick-start.md) or check [Troubleshooting](troubleshooting.md).
