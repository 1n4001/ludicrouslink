# HStreamer Project Structure

Complete overview of the project organization.

## Directory Tree

```
hstreamer/
│
├── android-app/                    # Part 1: Android RTSP Server
│   ├── app/
│   │   ├── src/
│   │   │   └── main/
│   │   │       ├── java/com/cesicorp/hstreamer/
│   │   │       │   ├── MainActivity.kt           # Main UI activity
│   │   │       │   ├── StreamingService.kt       # Foreground service
│   │   │       │   ├── ScreenEncoder.kt          # H.264 video encoder
│   │   │       │   ├── AudioEncoder.kt           # AAC audio encoder
│   │   │       │   └── RtspServer.kt             # RTSP server implementation
│   │   │       ├── res/
│   │   │       │   ├── layout/
│   │   │       │   │   └── activity_main.xml     # Main UI layout
│   │   │       │   └── values/
│   │   │       │       └── strings.xml           # String resources
│   │   │       └── AndroidManifest.xml           # App manifest
│   │   └── build.gradle                          # App build configuration
│   ├── gradle/
│   │   └── wrapper/
│   │       └── gradle-wrapper.properties         # Gradle wrapper config
│   ├── build.gradle                              # Project build config
│   ├── settings.gradle                           # Project settings
│   └── gradle.properties                         # Gradle properties
│
├── pi-gateway/                     # Part 2: Raspberry Pi Gateway
│   ├── gateway.py                                # Main gateway script
│   ├── requirements.txt                          # Python dependencies
│   ├── setup.sh                                  # Installation script
│   ├── hstreamer-gateway.service                 # Systemd service file
│   └── INSTALL_SERVICE.md                        # Service installation guide
│
├── web-client/                     # Part 3: Web Browser Client
│   ├── index.html                                # Main HTML page
│   ├── style.css                                 # Stylesheet
│   ├── client.js                                 # WebSocket client logic
│   └── opus-decoder.min.js                       # Opus audio decoder (v0.7.7)
│
├── docs/                           # Documentation
│   ├── QUICKSTART.md                             # Quick start guide
│   ├── NETWORK_SETUP.md                          # Network configuration
│   ├── TROUBLESHOOTING.md                        # Troubleshooting guide
│   └── API.md                                    # API documentation
│
├── README.md                       # Main documentation
└── PROJECT_STRUCTURE.md            # This file
```

## Component Details

### Part 1: Android RTSP Server App

**Language:** Kotlin
**Min API:** 29 (Android 10)
**Target API:** 34 (Android 14)

**Key Files:**

| File | Lines | Purpose |
|------|-------|---------|
| MainActivity.kt | ~150 | UI, permissions, service control |
| StreamingService.kt | ~120 | Foreground service, lifecycle management |
| ScreenEncoder.kt | ~200 | Screen capture, H.264 encoding |
| AudioEncoder.kt | ~180 | Audio capture, AAC encoding |
| RtspServer.kt | ~250 | RTSP protocol, client handling |

**Dependencies:**
- AndroidX Core KTX
- AndroidX AppCompat
- Material Design Components
- ConstraintLayout
- RTMP/RTSP Stream Client (pedroSG94)

**Permissions Required:**
- `INTERNET` - Network communication
- `FOREGROUND_SERVICE` - Background operation
- `FOREGROUND_SERVICE_MEDIA_PROJECTION` - Screen capture
- `RECORD_AUDIO` - Audio capture
- `WAKE_LOCK` - Prevent sleep
- `POST_NOTIFICATIONS` - Notification display

**Network:**
- Listens on: TCP port 8554
- Protocol: RTSP
- Stream path: `/live`

### Part 2: Raspberry Pi Gateway

**Language:** Python 3.8+
**Framework:** asyncio, GStreamer

**Key Files:**

| File | Lines | Purpose |
|------|-------|---------|
| gateway.py | ~350 | Main application, GStreamer pipeline, WebSocket server |
| setup.sh | ~40 | Dependency installation script |
| requirements.txt | ~5 | Python package list |

**Python Classes:**

1. **GStreamerRTSPClient**
   - Connects to Android RTSP stream
   - Decodes H.264 video
   - Converts to JPEG frames
   - Outputs to queue

2. **WebSocketServer**
   - Accepts web client connections
   - Broadcasts JPEG frames
   - Handles keepalive pings

3. **Gateway**
   - Coordinates RTSP client and WebSocket server
   - Manages frame queue
   - Handles lifecycle

**Dependencies:**
- websockets >= 12.0
- PyGObject >= 3.46.0
- numpy >= 1.24.0
- pillow >= 10.0.0
- GStreamer 1.0+ (system package)

**Network:**
- Connects to: Android device on port 8554
- Listens on: TCP port 8765
- Protocol: WebSocket

### Part 3: Web Browser Client

**Language:** JavaScript (ES6+)
**Framework:** Vanilla JS (no dependencies)

**Key Files:**

| File | Lines | Purpose |
|------|-------|---------|
| index.html | ~120 | UI structure, controls |
| style.css | ~350 | Responsive styling, animations |
| client.js | ~250 | WebSocket client, canvas rendering, metrics |

**JavaScript Classes:**

1. **HStreamerClient**
   - Manages WebSocket connection
   - Decodes base64 JPEG frames
   - Renders to HTML5 Canvas
   - Tracks FPS and latency
   - Handles fullscreen and screenshots

**Features:**
- Real-time video display
- FPS counter
- Latency monitor
- Fullscreen mode
- Screenshot capture
- Connection status
- Responsive design

**Browser Requirements:**
- Modern browser (Chrome 80+, Firefox 75+, Safari 13+, Edge 80+)
- JavaScript enabled
- HTML5 Canvas support
- WebSocket support

## Data Flow

```
┌─────────────────────────────────────────────────────────────┐
│ ANDROID DEVICE                                              │
│                                                             │
│  Screen/Audio  → MediaCodec → ScreenEncoder/AudioEncoder   │
│                                        ↓                     │
│                                   H.264/AAC NAL Units       │
│                                        ↓                     │
│                                   RtspServer                 │
│                                        ↓                     │
└────────────────────────────────────────┼───────────────────┘
                                         │
                                    RTSP Stream
                                  (Port 8554/TCP)
                                         │
┌────────────────────────────────────────┼───────────────────┐
│ RASPBERRY PI GATEWAY                   ↓                   │
│                                                             │
│  GStreamer Pipeline:                                        │
│  rtspsrc → rtph264depay → avdec_h264 → videoconvert →      │
│  videoscale → jpegenc → appsink                            │
│                                        ↓                     │
│                                  JPEG Frames                │
│                                        ↓                     │
│                                  Frame Queue                 │
│                                        ↓                     │
│                              WebSocketServer                 │
│                                        ↓                     │
└────────────────────────────────────────┼───────────────────┘
                                         │
                                  WebSocket Stream
                                 (Port 8765/TCP)
                                         │
┌────────────────────────────────────────┼───────────────────┐
│ WEB BROWSER                            ↓                   │
│                                                             │
│  WebSocket Client → Base64 Decode → Image → Canvas         │
│                                                             │
│  User sees real-time video stream                          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## Protocol Stack

### Android → Gateway (RTSP/RTP)

```
┌─────────────────┐
│   H.264/AAC     │  Application (Media)
├─────────────────┤
│   RTP/RTCP      │  Transport (Real-time)
├─────────────────┤
│   RTSP          │  Session Control
├─────────────────┤
│   TCP           │  Transport
├─────────────────┤
│   IP            │  Network
├─────────────────┤
│   Wi-Fi         │  Data Link
└─────────────────┘
```

### Gateway → Browser (WebSocket)

```
┌─────────────────┐
│   JSON/Base64   │  Application (Data)
├─────────────────┤
│   WebSocket     │  Application Protocol
├─────────────────┤
│   TCP           │  Transport
├─────────────────┤
│   IP            │  Network
├─────────────────┤
│   Wi-Fi/Ethernet│  Data Link
└─────────────────┘
```

## Build & Deployment

### Android App Build

```bash
cd android-app
./gradlew assembleDebug        # Build debug APK
./gradlew assembleRelease      # Build release APK
./gradlew installDebug         # Build and install
```

**Output:**
- `android-app/app/build/outputs/apk/debug/app-debug.apk`
- `android-app/app/build/outputs/apk/release/app-release.apk`

### Gateway Deployment

```bash
# Copy to Raspberry Pi
scp -r pi-gateway pi@raspberrypi.local:~/

# On Raspberry Pi
cd ~/pi-gateway
chmod +x setup.sh
./setup.sh
```

### Web Client Deployment

**Option 1: Local File**
```bash
cd web-client
open index.html  # macOS
start index.html  # Windows
xdg-open index.html  # Linux
```

**Option 2: HTTP Server**
```bash
cd web-client
python3 -m http.server 8080
# Visit http://localhost:8080
```

**Option 3: Web Server**
```bash
# Copy to Apache/Nginx web root
sudo cp web-client/* /var/www/html/hstreamer/
```

## Configuration Files

### Android Configuration

**Port Settings:**
```kotlin
// StreamingService.kt
private const val PORT = 8554
```

**Video Settings:**
```kotlin
// ScreenEncoder.kt
private var width = 1280
private var height = 720
private val fps = 30
private val bitrate = 2000000
```

**Audio Settings:**
```kotlin
// AudioEncoder.kt
private val sampleRate = 44100
private val bitrate = 128000
```

### Gateway Configuration

**Command Line:**
```bash
python3 gateway.py \
  --rtsp-url rtsp://192.168.1.100:8554/live \
  --ws-host 0.0.0.0 \
  --ws-port 8765
```

**Pipeline Quality:**
```python
# gateway.py, in _run_pipeline()
"jpegenc quality=85 ! "  # 0-100, higher = better quality
"videoscale ! video/x-raw,width=1280,height=720 ! "  # Resolution
```

### Web Client Configuration

**Default URL:**
```html
<!-- index.html -->
<input type="text" id="wsUrl" value="ws://localhost:8765">
```

**Performance:**
```javascript
// client.js
emit-signals=true max-buffers=1 drop=true  // Low latency
```

## Testing

### Unit Testing

**Android:**
```bash
cd android-app
./gradlew test
./gradlew connectedAndroidTest
```

**Gateway:**
```bash
cd pi-gateway
python3 -m pytest tests/
```

### Integration Testing

**RTSP Stream:**
```bash
vlc rtsp://192.168.1.100:8554/live
```

**WebSocket:**
```bash
wscat -c ws://192.168.1.101:8765
```

**End-to-End:**
1. Start Android app
2. Start gateway
3. Connect web client
4. Verify video streaming

## Performance Characteristics

### Latency

| Component | Typical Latency |
|-----------|----------------|
| Screen Capture | 16-33ms (30-60 fps) |
| H.264 Encoding | 10-20ms |
| Network (Wi-Fi) | 5-50ms |
| H.264 Decoding | 10-20ms |
| JPEG Encoding | 5-15ms |
| WebSocket | 1-5ms |
| Browser Rendering | 16ms (60fps) |
| **Total** | **63-179ms** |

### Bandwidth

| Resolution | FPS | Bitrate | Bandwidth |
|------------|-----|---------|-----------|
| 640x480 | 30 | 1 Mbps | 1.5 Mbps |
| 1280x720 | 30 | 2 Mbps | 3 Mbps |
| 1920x1080 | 30 | 4 Mbps | 6 Mbps |
| 1920x1080 | 60 | 8 Mbps | 12 Mbps |

### Resource Usage

**Android:**
- CPU: 10-20%
- RAM: 50-100 MB
- Battery: Moderate drain

**Raspberry Pi:**
- CPU: 30-60% (Pi 4)
- RAM: 100-200 MB
- Network: 2-8 Mbps

**Web Browser:**
- CPU: 5-15%
- RAM: 50-150 MB
- GPU: Minimal

## Security Considerations

### Current Implementation

- **No authentication** - Anyone on network can connect
- **No encryption** - Streams sent in clear text
- **No authorization** - No access control

### Recommended Improvements

1. **Add RTSP authentication**
2. **Use WSS (WebSocket Secure)** instead of WS
3. **Implement access tokens** for web clients
4. **Add rate limiting** to prevent abuse
5. **Use VPN** for remote access
6. **Enable firewall rules** on all devices

## License & Credits

**License:** Open source (specify your license)

**Credits:**
- Android MediaCodec API
- GStreamer multimedia framework
- WebSocket protocol (RFC 6455)
- RTSP protocol (RFC 2326)

## Support & Contribution

- **Issues:** Report bugs and request features
- **Pull Requests:** Contributions welcome
- **Documentation:** Help improve docs
- **Testing:** Test on different devices

## Changelog

**v1.0.0 (Current)**
- Initial release
- Basic RTSP streaming
- GStreamer gateway
- Web client with controls
- Comprehensive documentation

---

For detailed usage instructions, see [README.md](README.md).
For API reference, see [docs/API.md](docs/API.md).
For troubleshooting, see [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).
