# LudicrousLink Quick Start Guide

Get up and running with LudicrousLink in 5 minutes!

## Prerequisites Checklist

- [ ] Android device with Android 10+
- [ ] Raspberry Pi (3/4/5) with Raspbian OS
- [ ] All devices connected to the same Wi-Fi network
- [ ] Computer with web browser

## Quick Setup

### 1. Install Android App (2 minutes)

```bash
cd android-app
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open in Android Studio and click "Run".

### 2. Setup Raspberry Pi Gateway (2 minutes)

On your Raspberry Pi:

```bash
# Install dependencies
sudo apt-get update
sudo apt-get install -y gstreamer1.0-tools gstreamer1.0-rtsp python3-gst-1.0 python3-gi

# Install Python packages
pip3 install websockets PyGObject

# Navigate to gateway directory
cd pi-gateway
```

### 3. Start Streaming (1 minute)

**On Android:**
1. Open LudicrousLink app
2. Tap "Start Streaming"
3. Grant permissions
4. Note your IP (e.g., 192.168.1.100)

**On Raspberry Pi:**
```bash
python3 gateway.py --rtsp-url rtsp://192.168.1.100:8554/live
```

**On Web Browser:**
1. Open `web-client/index.html`
2. Enter: `ws://[Pi-IP]:8765`
3. Click "Connect"
4. **Done!** You should see your Android screen streaming!

## First-Time Troubleshooting

### Can't connect to Android RTSP stream?

**Quick fix:**
```bash
# Test with VLC first
vlc rtsp://192.168.1.100:8554/live
```

If VLC doesn't work, check:
- Android firewall settings
- Correct IP address
- Both devices on same network

### Gateway not receiving frames?

**Check GStreamer installation:**
```bash
gst-inspect-1.0 rtspsrc
```

Should show plugin info. If not, reinstall GStreamer.

### Web client can't connect?

**Quick fix:**
```bash
# Test WebSocket connectivity
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" \
  http://[Pi-IP]:8765/
```

## Default Network Ports

| Component | Port | Protocol | Purpose |
|-----------|------|----------|---------|
| Android App | 8554 | RTSP | Stream source |
| Pi Gateway | 8765 | WebSocket | Web client connection |

## Finding IP Addresses

**Android:**
- Settings → About Phone → Status → IP Address
- Or check in LudicrousLink app UI

**Raspberry Pi:**
```bash
hostname -I
```

**Your Computer:**
- Windows: `ipconfig`
- Mac/Linux: `ifconfig` or `ip addr`

## Quick Commands Reference

### Android (via adb)

```bash
# Check if app is running
adb shell ps | grep ludicrouslink

# View logs
adb logcat | grep LudicrousLink

# Get device IP
adb shell ip addr show wlan0
```

### Raspberry Pi Gateway

```bash
# Start gateway
python3 gateway.py --rtsp-url rtsp://[android-ip]:8554/live

# Run in background
nohup python3 gateway.py --rtsp-url rtsp://[android-ip]:8554/live &

# Check if running
ps aux | grep gateway
```

### Web Client

```bash
# Serve locally
python3 -m http.server 8080

# Then open: http://localhost:8080
```

## Performance Tips

### For Best Results:

1. **Use 5GHz Wi-Fi** - Better bandwidth and less interference
2. **Minimize distance** - Keep devices close to Wi-Fi router
3. **Close other apps** - Free up Android CPU and memory
4. **Use wired connection** - Connect Pi to router via Ethernet

### Reduce Latency:

- Lower resolution (edit `ScreenEncoder.kt`)
- Increase FPS to 60 (smoother but more bandwidth)
- Use Pi 4/5 (better processing power)

### Improve Quality:

- Increase bitrate (edit `ScreenEncoder.kt`)
- Increase JPEG quality (edit `gateway.py`)
- Use 1080p resolution

## Next Steps

Once basic streaming works:

1. **Optimize settings** - Adjust resolution, bitrate, FPS
2. **Set up systemd service** - Auto-start gateway on boot
3. **Configure port forwarding** - Access from anywhere (advanced)
4. **Add authentication** - Secure your stream (advanced)

## Common Quick Fixes

| Problem | Quick Solution |
|---------|----------------|
| "Permission denied" on Android | Restart app, grant all permissions |
| Gateway crashes immediately | Check RTSP URL format and IP |
| Black screen in browser | Wait 5-10 seconds for first frame |
| High latency (>1 second) | Check Wi-Fi signal strength |
| Choppy video | Lower resolution or FPS |

## Getting Help

1. Check main README.md for detailed troubleshooting
2. Review GStreamer logs: Look for ERROR messages
3. Check browser console (F12): Look for WebSocket errors
4. Test each component separately: Android → VLC → Gateway → Browser

## Success Checklist

After setup, you should have:

- [ ] Android app showing "Streaming Active"
- [ ] Gateway terminal showing "Pipeline started successfully"
- [ ] Gateway showing "Client connected" when browser connects
- [ ] Browser showing FPS > 20 and Latency < 500ms
- [ ] Smooth video playback with no major stuttering

Congratulations! Your LudicrousLink system is now operational. 🎉
