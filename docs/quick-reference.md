# LudicrousLink v2 - Quick Reference

## Architecture

```
┌─────────────────┐                    ┌──────────────────────┐
│  Android Device │                    │   Raspberry Pi       │
│                 │                    │                      │
│  [RtmpStreamer] │ ────RTMP Push───> │  nginx-rtmp :1935    │
│  [Discovery]    │ <───mDNS Advert── │  [Zeroconf]          │
│                 │                    │  gateway_v2.py       │
└─────────────────┘                    │  [GStreamer]         │
                                       │  WebSocket :8765     │
                                       └──────────┬───────────┘
                                                  │
                                       ┌──────────▼───────────┐
                                       │   Web Browser        │
                                       │   [Canvas Display]   │
                                       └──────────────────────┘
```

## Quick Commands

### Setup (One-Time)

```bash
# Raspberry Pi
cd pi-gateway
chmod +x setup_v2.sh
./setup_v2.sh

# Android
cd android-app
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Running

```bash
# Gateway
python3 gateway_v2.py

# Android
# Open app → Select gateway → Start Streaming

# Browser
# Open web-client/index.html → Connect to ws://[pi-ip]:8765
```

## Network Ports

| Service | Port | Protocol | Direction | Purpose |
|---------|------|----------|-----------|---------|
| nginx-rtmp | 1935 | TCP | Android → Gateway | RTMP push |
| WebSocket | 8765 | TCP | Browser ← Gateway | Frame delivery |
| mDNS | 5353 | UDP | Broadcast | Service discovery |
| nginx-stats | 8080 | TCP | Optional | Monitoring |

## Service Discovery

**Gateway advertises:**
```
Service: _ludicrouslink._tcp.local.
Name: LudicrousLink Gateway
Port: 1935
```

**Android discovers:**
```kotlin
NsdManager.discoverServices("_ludicrouslink._tcp.")
→ Resolves IP and port
→ Shows in dropdown
```

## URLs

| Component | Format | Example |
|-----------|--------|---------|
| RTMP Push | `rtmp://[gateway-ip]:1935/live/stream` | `rtmp://192.168.1.101:1935/live/stream` |
| WebSocket | `ws://[gateway-ip]:8765` | `ws://192.168.1.101:8765` |
| nginx Stats | `http://[gateway-ip]:8080/stat` | `http://192.168.1.101:8080/stat` |

## Key Files

### Android
```
ServiceDiscovery.kt    - mDNS discovery
RtmpStreamer.kt        - RTMP push client
StreamingService.kt    - Background streaming
MainActivity.kt        - UI with gateway selector
```

### Gateway
```
gateway_v2.py                - Main gateway script
setup_v2.sh                  - Installation script
/etc/nginx/nginx.conf        - nginx-rtmp configuration
```

## Configuration

### Gateway Options

```bash
python3 gateway_v2.py \
  --rtmp-port 1935           # RTMP port (default: 1935)
  --ws-port 8765             # WebSocket port (default: 8765)
  --name "Living Room"       # Service name for discovery
```

### nginx-rtmp Config

```nginx
# /etc/nginx/nginx.conf
rtmp {
    server {
        listen 1935;
        application live {
            live on;
            record off;  # Change to 'all' to record
        }
    }
}
```

### Android Settings

**In RtmpStreamer.kt:**
```kotlin
private val width = 1280        // Video width
private val height = 720        // Video height
private val fps = 30            // Frame rate
private val videoBitrate = 2000 * 1024  // 2 Mbps
```

## Monitoring

```bash
# nginx status
sudo systemctl status nginx

# Gateway is running
ps aux | grep gateway_v2

# Active streams
curl http://localhost:8080/stat

# nginx logs
sudo tail -f /var/log/nginx/error.log

# Gateway logs
python3 gateway_v2.py  # Watch console output

# Android logs
adb logcat | grep LudicrousLink
```

## Testing

### Test mDNS Discovery

```bash
# Linux/Mac
avahi-browse -r _ludicrouslink._tcp

# Expected output:
# _ludicrouslink._tcp.local
#   hostname = raspberrypi.local
#   port = [1935]
```

### Test RTMP Server

```bash
# Push test stream
ffmpeg -re -i test.mp4 -c copy -f flv rtmp://localhost:1935/live/stream

# Pull test stream
ffplay rtmp://localhost:1935/live/stream
```

### Test GStreamer Pipeline

```bash
# Test rtmpsrc
gst-launch-1.0 rtmpsrc location=rtmp://127.0.0.1:1935/live/stream ! fakesink

# Full pipeline test
gst-launch-1.0 rtmpsrc location=rtmp://127.0.0.1:1935/live/stream ! \
  flvdemux ! h264parse ! avdec_h264 ! autovideosink
```

## Troubleshooting

| Problem | Check | Solution |
|---------|-------|----------|
| No gateways found | mDNS working? | `avahi-browse -a` |
| Can't connect RTMP | nginx running? | `sudo systemctl restart nginx` |
| No frames | Pipeline error? | `GST_DEBUG=3 python3 gateway_v2.py` |
| Discovery fails | Same network? | Check Wi-Fi, disable VPN |
| Stream refused | Firewall? | `sudo ufw allow 1935/tcp` |

## Common Commands

```bash
# Start gateway as service
sudo systemctl start ludicrouslink-gateway

# Stop gateway
sudo systemctl stop ludicrouslink-gateway

# Gateway logs (if running as service)
sudo journalctl -u ludicrouslink-gateway -f

# Restart nginx
sudo systemctl restart nginx

# Check what's listening
sudo netstat -tuln | grep -E '1935|8765'

# Test network connectivity
ping [gateway-ip]
nmap -p 1935,8765 [gateway-ip]
```

## Performance Tuning

### Lower Latency
```kotlin
// Android: RtmpStreamer.kt
private val videoBitrate = 1500 * 1024  // Reduce bitrate
private val fps = 60                     // Increase FPS
```

```python
# Gateway: gateway_v2.py
"jpegenc quality=60 ! "  # Lower JPEG quality
"videoscale ! video/x-raw,width=960,height=540 ! "  # Lower resolution
```

### Better Quality
```kotlin
// Android: RtmpStreamer.kt
private val width = 1920
private val height = 1080
private val videoBitrate = 4000 * 1024  // 4 Mbps
```

```python
# Gateway: gateway_v2.py
"jpegenc quality=95 ! "  # Higher JPEG quality
```

## Status Indicators

### ✅ Healthy System

```
Gateway: "mDNS service registered"
Gateway: "Pipeline started successfully"
Android: "Found 1 gateway(s)"
Android: "Status: Streaming to [gateway-name]"
Browser: FPS > 20, Latency < 300ms
```

### ❌ Problem Indicators

```
Gateway: "Unable to set pipeline to PLAYING"
Android: "No gateways found"
Android: "Connection failed"
Browser: "No Signal" / FPS = 0
```

## Network Requirements

- **Bandwidth:** 2-8 Mbps (depending on resolution)
- **Latency:** < 50ms ping between devices
- **Network:** Same subnet, mDNS not blocked
- **Wi-Fi:** 5GHz recommended

## Version Differences

| Feature | v1 | v2 |
|---------|----|----|
| Protocol | RTSP | RTMP |
| Discovery | Manual | Automatic |
| Direction | Pull | Push |
| Server Location | Android | Gateway |
| Setup Complexity | Medium | Low |
| User Experience | Manual IP entry | Select from list |
| Scalability | 1:1 | N:1 |

## Integration Examples

### Stream Recording

```nginx
# In nginx.conf
application live {
    live on;
    record all;
    record_path /var/recordings;
    record_suffix -%Y%m%d-%H%M%S.flv;
}
```

### Multiple Android Devices

Each device connects to same gateway with unique stream names:
```
Device 1: rtmp://gateway:1935/live/device1
Device 2: rtmp://gateway:1935/live/device2
```

### Cloud Gateway

Point Android to remote server:
```
rtmp://streaming.example.com:1935/live/stream
```

## Security Considerations

**Current:** No authentication (local network only)

**Recommendations:**
- Use VPN for remote access
- Add nginx-rtmp authentication
- Enable SSL/TLS for WebSocket (WSS)
- Firewall rules to limit access

## Resources

- **nginx-rtmp:** https://github.com/arut/nginx-rtmp-module
- **GStreamer:** https://gstreamer.freedesktop.org/
- **Zeroconf/mDNS:** https://en.wikipedia.org/wiki/Zero-configuration_networking
- **Android NSD:** https://developer.android.com/training/connect-devices-wirelessly/nsd

---

**Quick Help:** For detailed docs, see `README_V2.md` and `docs/MIGRATION_V1_TO_V2.md`
