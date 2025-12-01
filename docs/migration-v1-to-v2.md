# Migration Guide: v1 → v2

This guide helps you migrate from HStreamer v1 (RTSP pull) to v2 (RTMP push with discovery).

## What's Changing

| Aspect | v1 (Old) | v2 (New) |
|--------|----------|----------|
| **Protocol** | RTSP (pull) | RTMP (push) |
| **Direction** | Gateway → Android | Android → Gateway |
| **Discovery** | Manual IP entry | Automatic via mDNS |
| **Server** | Android hosts RTSP | Gateway hosts RTMP |
| **Port** | 8554 (Android) | 1935 (Gateway) |
| **Configuration** | Gateway needs Android IP | Android discovers gateway |

## Step-by-Step Migration

### 1. Backup Current Setup (Optional)

```bash
# Backup old gateway
cd pi-gateway
cp gateway.py gateway_v1_backup.py

# Backup old Android APK
adb pull /data/app/*/base.apk hstreamer_v1_backup.apk
```

### 2. Update Raspberry Pi Gateway

```bash
cd pi-gateway

# Run new setup
chmod +x setup_v2.sh
./setup_v2.sh

# This installs:
# - nginx-rtmp-module
# - Zeroconf Python library
# - Configures nginx for RTMP
```

**Verify nginx-rtmp is running:**
```bash
sudo systemctl status nginx
sudo netstat -tuln | grep 1935
```

### 3. Update Android App

```bash
cd android-app

# Rebuild with new code
./gradlew clean
./gradlew assembleDebug

# Uninstall old version
adb uninstall com.cesicorp.hstreamer

# Install new version
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. Test New System

**On Raspberry Pi:**
```bash
python3 gateway_v2.py
```

Expected output:
```
[INFO] Starting HStreamer Gateway v2
[INFO] RTMP Server Port: 1935
[INFO] WebSocket Server: ws://0.0.0.0:8765
[INFO] mDNS service registered: HStreamer Gateway at 192.168.1.101:1935
[INFO] GStreamer RTMP receiver started
[INFO] Pipeline started successfully on port 1935
[INFO] WebSocket server started on ws://0.0.0.0:8765
[INFO] Frame broadcaster started
```

**On Android:**
1. Open app
2. Wait for "Found 1 gateway(s)"
3. Select gateway from dropdown
4. Tap "Start Streaming"

**In Browser:**
1. Connect to `ws://[gateway-ip]:8765`
2. View stream!

## Troubleshooting Migration

### Gateway Not Advertising

**Problem:** Android shows "No gateways found"

**Solution:**
```bash
# Check avahi is running
sudo systemctl status avahi-daemon

# If not, install and start
sudo apt-get install avahi-daemon
sudo systemctl enable avahi-daemon
sudo systemctl start avahi-daemon

# Test discovery
avahi-browse -r _hstreamer._tcp
```

### nginx-rtmp Not Running

**Problem:** Gateway fails to start

**Solution:**
```bash
# Check nginx status
sudo systemctl status nginx

# Check nginx configuration
sudo nginx -t

# If errors, reinstall
sudo apt-get install --reinstall nginx libnginx-mod-rtmp

# Restart nginx
sudo systemctl restart nginx
```

### Android NSD Not Working

**Problem:** Discovery starts but finds nothing

**Solution:**

1. Check Wi-Fi is connected (not cellular)
2. Ensure on same network as gateway
3. Check router allows mDNS (not blocked)
4. Try disabling VPN on Android
5. Check logs:
```bash
adb logcat | grep -E "ServiceDiscovery|NsdManager"
```

### RTMP Connection Refused

**Problem:** Android connects but streaming fails

**Solution:**

1. **Verify nginx-rtmp accepts connections:**
```bash
# Test with ffmpeg
ffmpeg -re -i test.mp4 -c copy -f flv rtmp://[gateway-ip]:1935/live/stream
```

2. **Check firewall:**
```bash
# On gateway
sudo ufw status
sudo ufw allow 1935/tcp
```

3. **Check nginx-rtmp logs:**
```bash
sudo tail -f /var/log/nginx/error.log
```

### Gateway Not Processing Stream

**Problem:** Stream pushed but no frames in browser

**Solution:**

1. **Verify rtmpsrc plugin:**
```bash
gst-inspect-1.0 rtmpsrc
```

2. **Test pipeline manually:**
```bash
gst-launch-1.0 rtmpsrc location=rtmp://127.0.0.1:1935/live/stream ! fakesink
```

3. **Enable debug logging:**
```bash
export GST_DEBUG=3
python3 gateway_v2.py
```

## Configuration Changes

### Old v1 Configuration

```bash
# v1: Gateway needed to know Android IP
python3 gateway.py --rtsp-url rtsp://192.168.1.100:8554/live
```

### New v2 Configuration

```bash
# v2: Gateway just starts and advertises
python3 gateway_v2.py --name "My Gateway"

# Optional: Customize ports
python3 gateway_v2.py --rtmp-port 1935 --ws-port 8765
```

## Running Both Versions Side-by-Side

You can temporarily run both v1 and v2:

```bash
# v1 gateway (old RTSP)
python3 gateway.py --rtsp-url rtsp://192.168.1.100:8554/live --ws-port 8765

# v2 gateway (new RTMP)
python3 gateway_v2.py --rtmp-port 1935 --ws-port 8766
```

Web clients can connect to either:
- v1: `ws://[pi-ip]:8765`
- v2: `ws://[pi-ip]:8766`

## What Gets Deleted (Safe to Remove)

After successful migration, you can remove v1 files:

**Android (now unused):**
- `ScreenEncoder.kt` - Replaced by RtmpStreamer library
- `AudioEncoder.kt` - Replaced by RtmpStreamer library
- `RtspServer.kt` - No longer hosting RTSP server

**Gateway:**
- `gateway.py` (v1) - Keep as `gateway_v1_backup.py`
- `setup.sh` (v1) - Replaced by `setup_v2.sh`

**Note:** Web client is unchanged!

## Rollback to v1

If you need to rollback:

```bash
# Gateway
python3 gateway.py --rtsp-url rtsp://[android-ip]:8554/live

# Android
adb install hstreamer_v1_backup.apk
```

## Advantages of v2

After migration you'll enjoy:

✅ **Zero Configuration** - No more manual IP entry
✅ **Better UX** - Select gateway from dropdown
✅ **Multiple Devices** - Multiple Androids → one gateway
✅ **Industry Standard** - RTMP (used by Twitch, YouTube, etc.)
✅ **Future Features** - Recording, transcoding, multi-bitrate

## Migration Checklist

- [ ] Backed up v1 setup (optional)
- [ ] Ran `setup_v2.sh` on gateway
- [ ] Verified nginx-rtmp is running (`systemctl status nginx`)
- [ ] Verified avahi-daemon is running
- [ ] Tested mDNS discovery (`avahi-browse -r _hstreamer._tcp`)
- [ ] Rebuilt and installed Android app
- [ ] Gateway starts with "mDNS service registered" message
- [ ] Android discovers gateway automatically
- [ ] Streaming works end-to-end
- [ ] Web browser client displays stream

## Getting Help

If you encounter issues during migration:

1. **Check v2 architecture** - Review README_V2.md
2. **Test each component** - nginx, mDNS, GStreamer separately
3. **Compare logs** - Check what's different from working v1 setup
4. **Network issues** - Verify mDNS not blocked by router/firewall

**Debug commands:**
```bash
# Gateway
sudo systemctl status nginx
sudo systemctl status avahi-daemon
avahi-browse -a
sudo netstat -tuln | grep -E '1935|8765'

# Android
adb logcat | grep -E "HStreamer|ServiceDiscovery|RtmpStreamer"

# Network
ping [gateway-ip]
nmap -p 1935,8765 [gateway-ip]
```

## Success Indicators

You know migration succeeded when:

1. Gateway logs show "mDNS service registered"
2. Android shows "Found 1 gateway(s)" within 10 seconds
3. Gateway dropdown populated with gateway name and IP
4. Streaming connects without errors
5. Browser receives frames
6. FPS > 20, Latency < 300ms

Congratulations on upgrading to v2! 🎉
