# HStreamer Quick Start Guide

## What is HStreamer?

HStreamer is a low-latency screen and audio streaming system that lets you stream your Android device's screen and internal audio to a web browser over your local network.

**Features:**
- Real-time screen streaming (720p @ 30fps)
- Internal audio streaming
- Low latency (~100-200ms)
- Automatic gateway discovery (no IP configuration needed)
- Web-based viewer (works on any device with a browser)

## Architecture

```
Android Device              Raspberry Pi Gateway           Web Browser
    │                              │                           │
    │  ┌──────────────────┐       │                           │
    │  │ Screen Capture   │       │                           │
    │  │ + Audio Capture  │       │                           │
    │  └────────┬─────────┘       │                           │
    │           │                  │                           │
    │      Encode H.264/AAC        │                           │
    │           │                  │                           │
    │     ┌─────▼──────┐           │                           │
    │     │ RTMP Push  ├──────────►│  nginx-rtmp              │
    │     └────────────┘           │       │                   │
    │                              │  ┌────▼──────┐            │
    │                              │  │ GStreamer │            │
    │                              │  │  Decode   │            │
    │                              │  │  Encode   │            │
    │                              │  └────┬──────┘            │
    │                              │       │                   │
    │                              │  ┌────▼──────────┐        │
    │                              │  │  HTTP Server  ├───────►│  View Stream
    │                              │  │  + WebSocket  │        │  + Listen Audio
    │                              │  └───────────────┘        │
    │                              │                           │
    │◄─────mDNS Discovery─────────►│                           │
```

## Prerequisites

### Raspberry Pi Gateway

**Hardware:**
- Raspberry Pi 3/4/5 (or any Linux machine)
- Network connection (Ethernet or WiFi)

**Software:**
```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Install nginx-rtmp
cd pi-gateway
chmod +x install_nginx_rtmp.sh
./install_nginx_rtmp.sh

# Install Python dependencies
sudo apt install -y python3-gi python3-gi-cairo gir1.2-gstreamer-1.0 \
    gir1.2-gst-plugins-base-1.0 gstreamer1.0-plugins-good \
    gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly \
    gstreamer1.0-libav gstreamer1.0-tools

# Install Python packages
pip3 install -r requirements.txt
```

### Android Device

**Requirements:**
- Android 10+ (API 29+)
- Same network as Raspberry Pi

**Build the App:**
1. Open `android-app/` in Android Studio
2. Build and install on your device

## Quick Start (3 Steps!)

### Step 1: Start the Gateway

```bash
cd pi-gateway
python3 gateway.py
```

**You should see:**
```
============================================================
HStreamer Gateway v2 - Starting
============================================================
RTMP Server Port: 1935
HTTP Server: http://0.0.0.0:8765
WebSocket: ws://0.0.0.0:8765/ws
Service Name: HStreamer Gateway
Features: Video + Audio streaming, Web UI
============================================================
mDNS service registered: HStreamer Gateway at 192.168.1.100:1935
GStreamer RTMP receiver started
HTTP server started on http://0.0.0.0:8765
WebSocket available at ws://0.0.0.0:8765/ws
Web client available at http://0.0.0.0:8765/
Video frame broadcaster started
Audio broadcaster started
Waiting for RTMP stream...
```

### Step 2: Start Android Streaming

1. Open HStreamer app on Android
2. Grant permissions:
   - Media projection (screen capture)
   - Audio recording (internal audio)
3. Wait for gateway to appear in dropdown (takes 2-3 seconds)
4. Select your gateway
5. Tap **"Start Streaming"**

**On Android, you'll see:**
- Notification: "Streaming to gateway"
- Green status indicator

**On gateway, you'll see:**
```
Attempt 1/30: Creating pipeline...
Pipeline started successfully!
Waiting for video and audio from RTMP stream...
```

### Step 3: View in Browser

Open your web browser to:
```
http://gateway-ip:8765/
```

**For example:**
- `http://192.168.1.100:8765/`
- `http://raspberrypi.local:8765/`

**The page will:**
1. Load automatically
2. Auto-detect WebSocket URL
3. Show "Disconnected" status

**Click "Connect"** and you should see:
- Video stream appears
- Audio starts playing
- FPS counter shows ~30 FPS
- Latency shows ~100-200ms

## That's It!

You're now streaming your Android screen and audio to your browser with low latency!

## Common Usage Patterns

### View from Multiple Devices

The stream can be viewed from multiple browsers simultaneously:
- Desktop computer: `http://gateway-ip:8765/`
- Laptop: `http://gateway-ip:8765/`
- Tablet: `http://gateway-ip:8765/`
- Phone: `http://gateway-ip:8765/`

Each viewer gets the same stream in real-time.

### Fullscreen Viewing

Click the **"Fullscreen"** button in the web client for immersive viewing.

### Take Screenshots

Click the **"Screenshot"** button to capture the current frame as a JPEG.

### Stop Streaming

On Android:
1. Pull down notification shade
2. Tap "Stop Streaming" on the HStreamer notification

Or from the app:
1. Open HStreamer app
2. Tap "Stop Streaming"

## Troubleshooting

### Gateway Not Appearing in Android App

**Check:**
1. Android and gateway on same network
2. mDNS/Zeroconf not blocked by firewall
3. Gateway shows "mDNS service registered"

**Fix:**
```bash
# Allow mDNS
sudo ufw allow 5353/udp

# Restart gateway
python3 gateway.py
```

### Video Not Appearing in Browser

**Check:**
1. Gateway logs show "Pipeline started successfully"
2. Browser console (F12) for errors
3. WebSocket shows "Connected" status

**Common Issues:**
- **Wrong WebSocket URL**: Should be `ws://gateway-ip:8765/ws`
- **Browser blocked autoplay**: Click anywhere on page
- **Firewall**: `sudo ufw allow 8765/tcp`

### No Audio in Browser

**Check:**
1. Browser console shows "Audio system initialized"
2. Volume not muted in browser
3. AudioContext state is "running" (not "suspended")

**Fix:**
- Click anywhere on page (browser autoplay policy)
- Check browser volume/mute state
- Restart stream from Android

### Low FPS or Choppy Video

**Check:**
1. Network latency: `ping gateway-ip`
2. CPU usage on Raspberry Pi: `htop`
3. Android encoding settings

**Optimize:**
```bash
# Use wired Ethernet instead of WiFi
# Reduce quality in Android app settings (if available)
# Use Raspberry Pi 4/5 for better performance
```

### Stream Disconnects Randomly

**Check:**
1. Network stability
2. Android battery optimization
3. Gateway logs for errors

**Fix:**
```bash
# Disable battery optimization for HStreamer app on Android
# Use wired Ethernet on Raspberry Pi
# Check for network interference
```

## Tips & Tricks

### Finding Gateway IP

```bash
# On gateway
hostname -I

# Or check mDNS
avahi-browse -rt _hstreamer._tcp
```

### Running Gateway as Service

Create `/etc/systemd/system/hstreamer.service`:

```ini
[Unit]
Description=HStreamer Gateway
After=network.target nginx.service

[Service]
Type=simple
User=pi
WorkingDirectory=/home/pi/hstreamer/pi-gateway
ExecStart=/usr/bin/python3 gateway.py
Restart=always

[Install]
WantedBy=multi-user.target
```

Enable and start:
```bash
sudo systemctl enable hstreamer
sudo systemctl start hstreamer
```

### Remote Access (Advanced)

Use SSH tunnel for remote access:

```bash
# On your local machine
ssh -L 8765:localhost:8765 pi@gateway-ip

# Access via
http://localhost:8765/
```

## Performance Tips

### Raspberry Pi
- Use Raspberry Pi 4 or 5 for best performance
- Use wired Ethernet instead of WiFi
- Overclock if needed (within safe limits)
- Close unnecessary processes

### Network
- Use 5GHz WiFi (if using WiFi)
- Minimize network hops
- Avoid network congestion
- Use QoS to prioritize streaming traffic

### Android
- Close background apps
- Use high-performance mode
- Keep device charging during long streams
- Disable battery optimization for HStreamer

## Advanced Options

### Custom Resolution

Edit `RtmpStreamer.kt` in Android app:
```kotlin
private val width = 1920  // Change from 1280
private val height = 1080 // Change from 720
```

### Custom Bitrate

Edit `RtmpStreamer.kt`:
```kotlin
private val videoBitrate = 4000 * 1024 // 4 Mbps instead of 2 Mbps
```

### Custom Audio Quality

Edit `gateway.py` pipeline:
```python
"opusenc bitrate=256000 ! "  # 256kbps instead of 128kbps
```

## Getting Help

### Check Logs

**Gateway logs:**
```bash
python3 gateway.py
# All output goes to console
```

**nginx-rtmp logs:**
```bash
sudo tail -f /var/log/nginx/error.log
```

**Android logs:**
```bash
adb logcat | grep HStreamer
```

### Common Log Messages

**Success:**
- "Pipeline started successfully!"
- "Broadcasting at ~30 FPS to 1 client(s)"
- "Audio broadcaster started"

**Errors:**
- "Max retries reached" - RTMP stream not arriving
- "Pipeline failed to start" - GStreamer issue
- "Port already in use" - Another service using port 8080/1935

## Next Steps

Now that you have basic streaming working, you can:

1. **Optimize Performance**: Adjust quality settings for your needs
2. **Autostart**: Set up systemd service for automatic startup
3. **Multiple Viewers**: Share the URL with others on your network
4. **Recording**: Add recording functionality (future enhancement)
5. **Mobile Viewing**: Access from tablets/phones on same network

## Summary

HStreamer provides a complete, low-latency streaming solution with:
- ✅ Automatic discovery
- ✅ Integrated web interface
- ✅ Video + audio streaming
- ✅ Simple 3-step setup
- ✅ Multiple simultaneous viewers
- ✅ Production-ready quality

Enjoy streaming! 🎥🔊
