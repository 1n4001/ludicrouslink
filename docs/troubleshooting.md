# HStreamer Troubleshooting Guide

Comprehensive guide to diagnosing and fixing common issues.

## Table of Contents

1. [Android App Issues](#android-app-issues)
2. [Gateway Issues](#gateway-issues)
3. [Web Client Issues](#web-client-issues)
4. [Network Issues](#network-issues)
5. [Performance Issues](#performance-issues)
6. [Audio Issues](#audio-issues)

---

## Android App Issues

### App Crashes on Startup

**Symptoms:**
- App closes immediately after launch
- "HStreamer has stopped" message

**Diagnosis:**
```bash
adb logcat | grep -E 'AndroidRuntime|HStreamer'
```

**Solutions:**

1. **Check Android version:**
   - Minimum required: Android 10 (API 29)
   - Check: Settings → About Phone → Android Version

2. **Clear app data:**
   ```bash
   adb shell pm clear com.cesicorp.hstreamer
   ```

3. **Reinstall app:**
   ```bash
   adb uninstall com.cesicorp.hstreamer
   adb install app-debug.apk
   ```

4. **Check for conflicting apps:**
   - Disable screen recording apps
   - Disable other streaming apps

### "Screen Capture Permission Denied"

**Symptoms:**
- Streaming doesn't start after tapping button
- Permission dialog doesn't appear

**Solutions:**

1. **Grant permissions manually:**
   - Settings → Apps → HStreamer → Permissions
   - Enable all requested permissions

2. **Check screen overlay:**
   - Disable apps with screen overlay (Facebook Messenger, etc.)
   - Settings → Apps → Special Access → Draw Over Other Apps

3. **Restart device:**
   - Some permissions require reboot to take effect

### "Audio Recording Permission Denied"

**Symptoms:**
- Video streams but no audio
- Permission dialog rejected

**Solutions:**

1. **Check microphone permission:**
   ```bash
   adb shell pm grant com.cesicorp.hstreamer android.permission.RECORD_AUDIO
   ```

2. **Verify Android version:**
   - AudioPlaybackCapture requires Android 10+
   - No workaround for older versions

3. **Check if another app is using microphone:**
   - Close all voice recording apps
   - Close Google Assistant
   - Disable voice wake-up features

### RTSP Server Won't Start

**Symptoms:**
- App shows "Streaming Active" but no connection possible
- Port 8554 not listening

**Diagnosis:**
```bash
adb shell netstat -tuln | grep 8554
```

**Solutions:**

1. **Check port availability:**
   ```bash
   # Kill apps using port 8554
   adb shell "su -c 'lsof -i :8554'"
   ```

2. **Check firewall (if rooted):**
   ```bash
   adb shell "su -c 'iptables -L'"
   ```

3. **Try different port:**
   - Edit `StreamingService.kt`
   - Change `PORT = 8554` to different value

### Encoder Initialization Failed

**Symptoms:**
- Logcat shows "Error setting up encoder"
- App crashes after starting streaming

**Diagnosis:**
```bash
adb logcat | grep MediaCodec
```

**Solutions:**

1. **Check codec availability:**
   ```bash
   adb shell dumpsys media.player | grep -i codec
   ```

2. **Reduce resolution:**
   - Edit `ScreenEncoder.kt`
   - Lower width/height values
   - Try 854x480 instead of 1280x720

3. **Reduce bitrate:**
   - Edit `ScreenEncoder.kt`
   - Lower bitrate to 1000000 (1 Mbps)

4. **Free up memory:**
   - Close background apps
   - Clear cached data

### Foreground Service Error

**Symptoms:**
- "ForegroundServiceStartNotAllowedException"
- Service doesn't start

**Solutions:**

1. **Android 12+ restriction:**
   - Start from user interaction (button press)
   - Don't auto-start on boot

2. **Check notification permission:**
   ```bash
   adb shell pm grant com.cesicorp.hstreamer android.permission.POST_NOTIFICATIONS
   ```

---

## Gateway Issues

### "Unable to set pipeline to PLAYING state"

**Symptoms:**
- Gateway crashes immediately
- GStreamer error in logs

**Diagnosis:**
```bash
GST_DEBUG=3 python3 gateway.py --rtsp-url rtsp://192.168.1.100:8554/live
```

**Solutions:**

1. **Check RTSP URL format:**
   ```
   Correct: rtsp://192.168.1.100:8554/live
   Wrong: http://192.168.1.100:8554/live
   Wrong: rtsp://192.168.1.100/live
   ```

2. **Test with gst-launch:**
   ```bash
   gst-launch-1.0 rtspsrc location=rtsp://192.168.1.100:8554/live latency=0 ! fakesink
   ```

3. **Check GStreamer plugins:**
   ```bash
   gst-inspect-1.0 rtspsrc
   gst-inspect-1.0 rtph264depay
   gst-inspect-1.0 avdec_h264
   ```

4. **Reinstall GStreamer:**
   ```bash
   sudo apt-get purge gstreamer1.0-*
   sudo apt-get install gstreamer1.0-tools gstreamer1.0-plugins-{base,good,bad,ugly} gstreamer1.0-libav
   ```

### "Connection refused to RTSP server"

**Symptoms:**
- Gateway can't connect to Android
- "Connection refused" error

**Diagnosis:**
```bash
# Test from gateway
telnet 192.168.1.100 8554

# Test with VLC
vlc rtsp://192.168.1.100:8554/live
```

**Solutions:**

1. **Verify Android is streaming:**
   - Check app shows "Streaming Active"
   - Check notification is present

2. **Check network connectivity:**
   ```bash
   ping 192.168.1.100
   ```

3. **Check firewall:**
   ```bash
   # On Pi
   sudo ufw status

   # Temporarily disable for testing
   sudo ufw disable
   ```

4. **Verify Android IP address:**
   - IP may have changed via DHCP
   - Check current IP in Android app

### "ModuleNotFoundError: No module named 'gi'"

**Symptoms:**
- Python import error
- Gateway won't start

**Solutions:**

1. **Install PyGObject:**
   ```bash
   sudo apt-get install python3-gi python3-gi-cairo gir1.2-gstreamer-1.0
   ```

2. **Install via pip:**
   ```bash
   pip3 install PyGObject
   ```

3. **Check Python version:**
   ```bash
   python3 --version  # Should be 3.8+
   ```

### WebSocket Server Won't Start

**Symptoms:**
- "Address already in use" error
- Port 8765 conflict

**Diagnosis:**
```bash
sudo netstat -tuln | grep 8765
sudo lsof -i :8765
```

**Solutions:**

1. **Kill existing process:**
   ```bash
   sudo kill $(lsof -t -i:8765)
   ```

2. **Use different port:**
   ```bash
   python3 gateway.py --rtsp-url rtsp://... --ws-port 8766
   ```

3. **Check for systemd service:**
   ```bash
   sudo systemctl stop hstreamer-gateway.service
   ```

### No Frames Being Received

**Symptoms:**
- Gateway connects but no frames
- Web client shows "No Signal"

**Diagnosis:**
```bash
# Enable verbose logging
GST_DEBUG=4 python3 gateway.py --rtsp-url rtsp://192.168.1.100:8554/live 2>&1 | grep -i "new-sample"
```

**Solutions:**

1. **Check H.264 decoder:**
   ```bash
   gst-inspect-1.0 avdec_h264
   ```

2. **Verify RTSP stream has data:**
   ```bash
   ffprobe rtsp://192.168.1.100:8554/live
   ```

3. **Try software decoding:**
   - Edit gateway.py pipeline
   - Replace `avdec_h264` with `x264dec` or `openh264dec`

4. **Check queue sizes:**
   ```python
   # In gateway.py, add logging
   logger.info(f"Frame queue size: {self.frame_queue.qsize()}")
   ```

### High CPU Usage

**Symptoms:**
- Raspberry Pi overheating
- Gateway process using 100% CPU

**Diagnosis:**
```bash
top -p $(pgrep -f gateway.py)
htop
```

**Solutions:**

1. **Reduce resolution:**
   - Edit pipeline to use smaller resolution
   - Add: `videoscale ! video/x-raw,width=640,height=480`

2. **Reduce JPEG quality:**
   - Change `jpegenc quality=85` to `quality=60`

3. **Reduce frame rate:**
   - Add `videorate ! video/x-raw,framerate=15/1`

4. **Use hardware acceleration:**
   ```bash
   # On Pi 4/5
   gst-launch-1.0 ... ! v4l2h264dec ! ...
   ```

---

## Web Client Issues

### "Connection Failed" or "Connection Refused"

**Symptoms:**
- Can't connect to WebSocket
- Browser console shows connection error

**Diagnosis:**
```javascript
// Open browser console (F12)
// Check for errors
```

**Solutions:**

1. **Verify gateway is running:**
   ```bash
   # On Pi
   ps aux | grep gateway
   sudo netstat -tuln | grep 8765
   ```

2. **Check WebSocket URL format:**
   ```
   Correct: ws://192.168.1.101:8765
   Wrong: http://192.168.1.101:8765
   Wrong: wss://192.168.1.101:8765 (requires SSL)
   ```

3. **Test with wscat:**
   ```bash
   npm install -g wscat
   wscat -c ws://192.168.1.101:8765
   ```

4. **Check browser security:**
   - Mixed content warning (if page is HTTPS)
   - Try in incognito mode
   - Disable browser extensions

### "No Signal" Despite Connection

**Symptoms:**
- Status shows "Connected"
- Black screen with "No Signal" overlay

**Diagnosis:**
```javascript
// Browser console
// Look for 'frame' messages
```

**Solutions:**

1. **Check if gateway is sending frames:**
   ```bash
   # In gateway logs
   grep -i "frame" gateway.log
   ```

2. **Verify base64 decoding:**
   ```javascript
   // In browser console
   // Check for image decode errors
   ```

3. **Check canvas size:**
   ```javascript
   console.log(document.getElementById('videoCanvas').width);
   ```

4. **Test with simple WebSocket client:**
   ```javascript
   const ws = new WebSocket('ws://192.168.1.101:8765');
   ws.onmessage = (e) => console.log(JSON.parse(e.data).type);
   ```

### Low Frame Rate (<10 FPS)

**Symptoms:**
- FPS counter shows low values
- Video appears choppy

**Diagnosis:**
```javascript
// Browser console - check frame receive rate
let count = 0;
setInterval(() => { console.log('FPS:', count); count = 0; }, 1000);
// Increment count in onMessage handler
```

**Solutions:**

1. **Check network throughput:**
   - Use browser Network tab
   - Check WebSocket message rate

2. **Check gateway performance:**
   ```bash
   top -p $(pgrep -f gateway)
   ```

3. **Reduce JPEG quality:**
   - Lower quality = faster encoding
   - Edit gateway.py pipeline

4. **Close other browser tabs:**
   - Free up browser resources
   - Disable extensions

### Canvas Not Displaying Image

**Symptoms:**
- No errors but canvas is blank
- Image loads but doesn't draw

**Diagnosis:**
```javascript
// Browser console
const ctx = document.getElementById('videoCanvas').getContext('2d');
console.log(ctx);
```

**Solutions:**

1. **Check canvas context:**
   ```javascript
   // Verify 2D context is available
   if (!ctx) {
     console.error('Canvas context not available');
   }
   ```

2. **Check image load:**
   ```javascript
   img.onerror = (e) => console.error('Image load error:', e);
   ```

3. **Verify base64 data:**
   ```javascript
   // Check first few characters
   console.log(base64Data.substring(0, 50));
   ```

4. **Try different browser:**
   - Test in Chrome, Firefox, Safari
   - Check for browser-specific issues

---

## Network Issues

### Devices Can't See Each Other

**Symptoms:**
- Ping fails between devices
- Can't connect despite being on same network

**Diagnosis:**
```bash
# From Pi to Android
ping 192.168.1.100

# Check routing
ip route

# Check ARP
arp -a
```

**Solutions:**

1. **Check network isolation:**
   - Router may have AP isolation enabled
   - Disable "Guest Network" mode
   - Check "Client Isolation" setting

2. **Verify subnet:**
   ```bash
   # All devices should be on same subnet
   # 192.168.1.x/24 for example
   ```

3. **Check firewall:**
   - Disable temporarily for testing
   - Add specific allow rules

4. **Try direct connection:**
   - Use Wi-Fi hotspot on Android
   - Connect Pi to Android's hotspot

### High Latency (>1 second)

**Symptoms:**
- Visible delay between action and display
- Latency counter shows >1000ms

**Diagnosis:**
```bash
# Check ping latency
ping -c 100 192.168.1.100

# Check for packet loss
ping -c 100 -i 0.2 192.168.1.100 | grep loss
```

**Solutions:**

1. **Improve Wi-Fi signal:**
   - Move closer to router
   - Remove obstructions
   - Switch to 5GHz band

2. **Reduce network congestion:**
   - Pause downloads/uploads
   - Limit other device activity
   - Enable QoS on router

3. **Optimize pipeline:**
   - Reduce buffer sizes
   - Lower resolution
   - Increase FPS (counterintuitive but reduces buffering)

4. **Check processing delay:**
   ```python
   # Add timing logs in gateway.py
   import time
   start = time.time()
   # ... processing ...
   print(f"Processing time: {(time.time() - start) * 1000}ms")
   ```

---

## Performance Issues

### Android Device Overheating

**Symptoms:**
- Device gets hot during streaming
- Performance degrades over time

**Solutions:**

1. **Reduce encoding load:**
   - Lower resolution
   - Lower bitrate
   - Lower FPS

2. **Improve cooling:**
   - Remove case
   - Point fan at device
   - Avoid direct sunlight

3. **Close background apps:**
   ```bash
   adb shell am kill-all
   ```

4. **Reduce screen brightness:**
   - Lower display brightness
   - Enable battery saver

### Raspberry Pi Throttling

**Symptoms:**
- CPU frequency reduced
- Performance drops

**Diagnosis:**
```bash
# Check for throttling
vcgencmd get_throttled

# Monitor temperature
watch -n 1 vcgencmd measure_temp

# Check CPU frequency
watch -n 1 'cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq'
```

**Solutions:**

1. **Improve cooling:**
   - Add heatsinks
   - Add fan (5V)
   - Improve case ventilation

2. **Increase power supply:**
   - Use official Pi power supply
   - Minimum 3A for Pi 4

3. **Optimize pipeline:**
   - Use hardware acceleration
   - Reduce resolution
   - Lower quality settings

4. **Overclock (carefully):**
   ```bash
   # Edit /boot/config.txt
   arm_freq=1800  # Pi 4
   over_voltage=4
   ```

---

## Audio Issues

### No Audio in Stream

**Symptoms:**
- Video works but no audio
- Android app captures audio but not transmitted

**Diagnosis:**
```bash
# Check if Android is encoding audio
adb logcat | grep AudioEncoder

# Check RTSP stream with ffprobe
ffprobe rtsp://192.168.1.100:8554/live
```

**Solutions:**

1. **Verify Android 10+:**
   - AudioPlaybackCapture requires Android 10+
   - Check: Settings → About Phone

2. **Check audio permission:**
   ```bash
   adb shell pm grant com.cesicorp.hstreamer android.permission.RECORD_AUDIO
   ```

3. **Verify audio track in stream:**
   ```bash
   vlc rtsp://192.168.1.100:8554/live
   # Check VLC → Tools → Codec Information → Codec Details
   ```

4. **Check AudioEncoder initialization:**
   ```bash
   adb logcat | grep "AudioEncoder"
   ```

### Audio Desynchronized from Video

**Symptoms:**
- Audio lags or leads video
- Lip sync issues

**Solutions:**

1. **Check timestamps:**
   - Ensure proper PTS in encoders
   - Verify timestamp alignment

2. **Adjust pipeline buffering:**
   - Add `audiobuffersplit` in GStreamer
   - Tune buffer sizes

3. **This implementation focuses on video:**
   - Current implementation prioritizes video
   - Audio sync requires additional RTP depayloading

**Note:** The provided implementation includes audio encoding on Android but the gateway focuses on video. Full audio support requires additional RTP audio processing in the gateway.

---

## Getting More Help

### Collecting Debug Information

```bash
# Android logs
adb logcat -d > android_logs.txt

# Gateway logs
GST_DEBUG=3 python3 gateway.py --rtsp-url rtsp://... > gateway_logs.txt 2>&1

# Network diagnostics
ping -c 50 192.168.1.100 > network_test.txt
traceroute 192.168.1.100 >> network_test.txt

# System information
uname -a > system_info.txt
cat /proc/cpuinfo >> system_info.txt
```

### Testing Components Individually

1. **Test Android RTSP stream:**
   ```bash
   vlc rtsp://192.168.1.100:8554/live
   ```

2. **Test Gateway GStreamer:**
   ```bash
   gst-launch-1.0 rtspsrc location=rtsp://192.168.1.100:8554/live ! fakesink
   ```

3. **Test WebSocket:**
   ```bash
   wscat -c ws://192.168.1.101:8765
   ```

### Useful Commands Reference

```bash
# Check if Android app is running
adb shell "ps -A | grep hstreamer"

# Check ports on Android
adb shell "netstat -tuln | grep 8554"

# Check ports on Pi
sudo netstat -tuln | grep -E '8554|8765'

# Monitor network traffic
sudo tcpdump -i wlan0 port 8554 or port 8765

# Test bandwidth
iperf3 -s  # On Pi
iperf3 -c 192.168.1.101  # On Android (Termux)
```

---

If problems persist after trying these solutions, check the GitHub issues page or create a new issue with:
- Complete error messages
- Log files
- System specifications
- Network configuration

Happy streaming! 🎬
