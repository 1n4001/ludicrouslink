# Audio Implementation Summary

## Overview
Added complete audio support to LudicrousLink v2, enabling real-time audio streaming alongside video from Android devices to web browsers.

## What Changed

### 1. Gateway (`pi-gateway/gateway.py`)

**Key Changes:**
- Added audio queue for processing audio packets
- Updated GStreamer pipeline to include audio branch:
  - Decodes AAC audio from RTMP stream
  - Resamples to 48kHz stereo
  - Encodes to Opus format (128kbps)
- Added separate video and audio appsink elements
- Implemented `_on_new_audio_sample()` callback
- Added audio broadcaster in WebSocket server
- New message type: `audio-frame` (alongside existing `video-frame`)

**GStreamer Pipeline:**
```
rtmpsrc → flvdemux
  ├─ video: h264parse → avdec_h264 → videoconvert → jpegenc → videosink
  └─ audio: aacparse → avdec_aac → audioconvert → audioresample → opusenc → audiosink
```

### 2. Web Client (`web-client/`)

**index.html:**
- Added Opus decoder library (local): `opus-decoder@0.7.7` (`opus-decoder.min.js`)

**client.js:**
- Added Web Audio API integration
- Implemented Opus decoder initialization
- Added `initAudio()` method for AudioContext setup
- Added `handleAudioFrame()` method for decoding and playback
- Updated message handler to process `audio-frame` messages
- Added audio cleanup on disconnect

## Architecture

### Audio Flow
```
Android App (AAC encoding)
    ↓ RTMP stream
nginx-rtmp server (port 1935)
    ↓ localhost connection
Gateway GStreamer pipeline
    ├─ Video: H.264 → JPEG → Base64
    └─ Audio: AAC → Opus → Base64
            ↓ WebSocket (port 8765)
Web Browser
    ├─ Video: Base64 → JPEG → Canvas
    └─ Audio: Base64 → Opus decoder → Web Audio API
```

## Testing

### 1. Start the Gateway
```bash
cd pi-gateway
python3 gateway.py
```

Expected output:
```
============================================================
LudicrousLink Gateway v2 - Starting
============================================================
RTMP Server Port: 1935
WebSocket Server: ws://0.0.0.0:8765
Service Name: LudicrousLink Gateway
Features: Video + Audio streaming
============================================================
mDNS service registered: LudicrousLink Gateway at 192.168.x.x:1935
GStreamer RTMP receiver started
WebSocket server started on ws://0.0.0.0:8765
Video frame broadcaster started
Audio broadcaster started
```

### 2. Start Android App
1. Open LudicrousLink app on Android
2. Wait for gateway to appear in the dropdown
3. Select the gateway
4. Tap "Start Streaming"

### 3. Open Web Client
```bash
cd web-client
python3 -m http.server 8000
```

Open browser to `http://localhost:8000`
- Enter gateway URL: `ws://gateway-ip:8765`
- Click "Connect"

### 4. Verify Audio
Check browser console for:
```
Audio system initialized: {
  sampleRate: 48000,
  channels: 2,
  state: "running"
}
```

## Technical Details

### Audio Specifications
- **Source Format**: AAC (from Android)
- **Transport Format**: Opus @ 128kbps, 48kHz stereo
- **Encoding**: Base64 for WebSocket transmission
- **Playback**: Web Audio API with AudioBuffer

### Latency Characteristics
- **Audio encoding latency**: ~20ms (Opus frame size)
- **Network latency**: ~10-50ms (LAN)
- **Decoding + playback**: ~10-20ms
- **Total audio latency**: ~40-90ms

This is slightly higher than video latency (~100-200ms for JPEG frames) but acceptable for LAN streaming.

### Browser Compatibility
- Chrome/Edge: Full support
- Firefox: Full support
- Safari: Full support (requires user interaction for AudioContext)

**Note**: Due to browser autoplay policies, audio may not start until user interacts with the page. The implementation handles this by resuming the AudioContext after connection.

## Troubleshooting

### No Audio in Browser
1. Check browser console for errors
2. Verify AudioContext state: Should be "running", not "suspended"
3. Click anywhere on the page to trigger audio context resume
4. Check gateway logs for "Audio broadcaster started"

### Audio Choppy or Stuttering
1. Check network latency
2. Increase audio queue size in gateway (currently 10 packets)
3. Check CPU usage on Raspberry Pi

### Audio/Video Sync Issues
This is expected - audio and video use separate queues and clocks. For better sync:
- Reduce frame processing latency
- Implement timestamp-based synchronization (future enhancement)

## Future Enhancements

1. **Audio/Video Synchronization**: Add timestamp-based sync
2. **Adaptive Bitrate**: Adjust Opus bitrate based on network conditions
3. **Audio Level Meter**: Display audio levels in web UI
4. **Volume Control**: Add volume slider in web client
5. **Audio Mute**: Add mute button in web client

## Files Modified

- `pi-gateway/gateway.py` - Added audio processing pipeline
- `web-client/index.html` - Added Opus decoder library
- `web-client/client.js` - Added audio decoding and playback

## Dependencies

### Gateway (Python)
- GStreamer plugins: `gst-plugins-good` (opusenc)
- No new Python packages required

### Web Client (JavaScript)
- `opus-decoder@0.7.7` (loaded from CDN)
- No build step required

## Verification

To verify audio is being captured and transmitted:

1. **Check Android logs**: Should show audio encoder starting
2. **Check gateway logs**:
   - "Waiting for video and audio from RTMP stream..."
   - "Audio broadcaster started"
3. **Check browser console**: "Audio system initialized"
4. **Check browser DevTools Network tab**: Should see WebSocket frames with `audio-frame` messages

## Notes

- Audio is encoded to Opus for better quality and lower latency than AAC
- Web Audio API provides low-latency playback
- Gateway processes audio and video in parallel threads
- WebSocket broadcasts video and audio frames independently
