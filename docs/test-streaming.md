# Test Streaming with FFmpeg

This guide shows how to test the LudicrousLink gateway using FFmpeg instead of an Android device.

## Option 1: Generate Test Video with FFmpeg

### Create a 10-second test video with audio

```bash
ffmpeg -f lavfi -i testsrc=duration=10:size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=1000:duration=10 \
       -c:v libx264 -preset ultrafast -tune zerolatency \
       -c:a aac -b:a 128k \
       test_video.mp4
```

**What this creates:**
- 10 seconds of test pattern video (color bars with timestamp)
- 1280x720 @ 30fps
- 1000Hz sine wave audio
- H.264 video + AAC audio
- File: `test_video.mp4`

### Create a longer test video (60 seconds)

```bash
ffmpeg -f lavfi -i testsrc=duration=60:size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=440:duration=60 \
       -c:v libx264 -preset ultrafast -tune zerolatency \
       -c:a aac -b:a 128k \
       test_video_60s.mp4
```

### Create test video with text overlay

```bash
ffmpeg -f lavfi -i testsrc=duration=30:size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=440:duration=30 \
       -vf "drawtext=text='LudicrousLink Test':fontsize=60:fontcolor=white:x=(w-text_w)/2:y=(h-text_h)/2" \
       -c:v libx264 -preset ultrafast -tune zerolatency \
       -c:a aac -b:a 128k \
       test_video_text.mp4
```

## Option 2: Use Existing Video

If you have an existing MP4 file, you can use it directly (skip to streaming section below).

## Stream Test Video to Gateway

### Stream on loop (recommended for testing)

```bash
ffmpeg -re -stream_loop -1 -i test_video.mp4 \
       -c:v copy -c:a copy \
       -f flv rtmp://localhost:1935/live/stream
```

**Parameters:**
- `-re`: Read input at native frame rate (real-time)
- `-stream_loop -1`: Loop indefinitely (-1 = infinite)
- `-i test_video.mp4`: Input file
- `-c:v copy -c:a copy`: Copy codecs (no re-encoding)
- `-f flv`: Output format (FLV for RTMP)
- `rtmp://localhost:1935/live/stream`: Gateway RTMP URL

### Stream with re-encoding (if codecs don't match)

```bash
ffmpeg -re -stream_loop -1 -i test_video.mp4 \
       -c:v libx264 -preset ultrafast -tune zerolatency -b:v 2M \
       -c:a aac -b:a 128k \
       -f flv rtmp://localhost:1935/live/stream
```

### Stream to remote gateway

```bash
ffmpeg -re -stream_loop -1 -i test_video.mp4 \
       -c:v copy -c:a copy \
       -f flv rtmp://192.168.1.100:1935/live/stream
```

Replace `192.168.1.100` with your gateway's IP address.

### Stream with limited loops (5 times)

```bash
ffmpeg -re -stream_loop 5 -i test_video.mp4 \
       -c:v copy -c:a copy \
       -f flv rtmp://localhost:1935/live/stream
```

## Complete Test Workflow

### Step 1: Start Gateway

```bash
cd pi-gateway
python3 gateway.py
```

Wait for:
```
HTTP server started on http://0.0.0.0:8765
WebSocket available at ws://0.0.0.0:8765/ws
Waiting for RTMP stream...
```

### Step 2: Generate Test Video

```bash
# In another terminal
ffmpeg -f lavfi -i testsrc=duration=10:size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=1000:duration=10 \
       -c:v libx264 -preset ultrafast -tune zerolatency \
       -c:a aac -b:a 128k \
       test_video.mp4
```

### Step 3: Stream to Gateway

```bash
ffmpeg -re -stream_loop -1 -i test_video.mp4 \
       -c:v copy -c:a copy \
       -f flv rtmp://localhost:1935/live/stream
```

**You should see:**
```
frame=  300 fps= 30 q=-1.0 size=    1024kB time=00:00:10.00 bitrate= 839.7kbits/s speed=   1x
```

**On gateway:**
```
Attempt 1/30: Creating pipeline...
Pipeline started successfully!
Waiting for video and audio from RTMP stream...
Broadcasting at ~30.0 FPS to 0 client(s)
```

### Step 4: Open Web Client

```bash
# Open browser to
http://localhost:8765/

# Click "Connect"
```

You should see the test pattern video and hear the sine wave audio!

## Advanced Test Patterns

### Scrolling text test

```bash
ffmpeg -f lavfi -i testsrc=duration=30:size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=440:duration=30 \
       -vf "drawtext=text='Frame %{n}':fontsize=40:fontcolor=white:x=100:y=100" \
       -c:v libx264 -preset ultrafast -tune zerolatency \
       -c:a aac -b:a 128k \
       test_scrolling.mp4

ffmpeg -re -stream_loop -1 -i test_scrolling.mp4 \
       -c:v copy -c:a copy \
       -f flv rtmp://localhost:1935/live/stream
```

### Color cycle test

```bash
ffmpeg -f lavfi -i testsrc2=duration=30:size=1280x720:rate=30 \
       -f lavfi -i sine=frequency=440:duration=30 \
       -c:v libx264 -preset ultrafast -tune zerolatency \
       -c:a aac -b:a 128k \
       test_color.mp4

ffmpeg -re -stream_loop -1 -i test_color.mp4 \
       -c:v copy -c:a copy \
       -f flv rtmp://localhost:1935/live/stream
```

### Multiple audio frequencies test

```bash
# Create sweeping tone from 440Hz to 880Hz
ffmpeg -f lavfi -i testsrc=duration=30:size=1280x720:rate=30 \
       -f lavfi -i "sine=frequency=440+440*t/30:duration=30" \
       -c:v libx264 -preset ultrafast -tune zerolatency \
       -c:a aac -b:a 128k \
       test_sweep.mp4

ffmpeg -re -stream_loop -1 -i test_sweep.mp4 \
       -c:v copy -c:a copy \
       -f flv rtmp://localhost:1935/live/stream
```

## Troubleshooting

### "Connection refused" error

**Problem:**
```
[tcp @ 0x...] Connection to tcp://localhost:1935 failed
```

**Solution:**
Check nginx-rtmp is running:
```bash
sudo systemctl status nginx
sudo netstat -tuln | grep 1935
```

If not running:
```bash
sudo systemctl start nginx
```

### "Real-time buffer too full" warning

**Problem:**
```
[flv @ 0x...] Real-time buffer [N%] full, dropping frames
```

**Solution:**
This is normal and expected - it means ffmpeg is keeping up with real-time streaming.

### Gateway not receiving stream

**Check gateway logs:**
- Should show "Pipeline started successfully!"
- If showing "Max retries reached", the stream isn't arriving

**Verify RTMP URL:**
```bash
# Should be exactly this for local testing
rtmp://localhost:1935/live/stream

# Or for remote gateway
rtmp://gateway-ip:1935/live/stream
```

### No audio in browser

**Check:**
1. Test video has audio: `ffprobe test_video.mp4` (should show audio stream)
2. FFmpeg command includes audio: `-c:a aac` or `-c:a copy`
3. Browser console shows "Audio system initialized"

### Video choppy or dropping frames

**Solutions:**
1. Use `-c:v copy -c:a copy` (no re-encoding)
2. Reduce video bitrate: `-b:v 1M`
3. Use faster preset: `-preset veryfast`
4. Check CPU usage on gateway

## Using Real Videos

### Convert any video to compatible format

```bash
ffmpeg -i input_video.mp4 \
       -c:v libx264 -preset fast -profile:v baseline \
       -c:a aac -b:a 128k \
       -vf scale=1280:720 \
       output_compatible.mp4
```

### Stream real video

```bash
ffmpeg -re -stream_loop -1 -i your_video.mp4 \
       -c:v copy -c:a copy \
       -f flv rtmp://localhost:1935/live/stream
```

### Stream from webcam (Linux)

```bash
ffmpeg -f v4l2 -i /dev/video0 \
       -f alsa -i hw:0 \
       -c:v libx264 -preset ultrafast -tune zerolatency -b:v 2M \
       -c:a aac -b:a 128k \
       -f flv rtmp://localhost:1935/live/stream
```

### Stream from screen capture (Linux)

```bash
ffmpeg -f x11grab -video_size 1280x720 -framerate 30 -i :0.0 \
       -f pulse -i default \
       -c:v libx264 -preset ultrafast -tune zerolatency -b:v 2M \
       -c:a aac -b:a 128k \
       -f flv rtmp://localhost:1935/live/stream
```

## Performance Tips

### For low-latency streaming:

```bash
ffmpeg -re -stream_loop -1 -i test_video.mp4 \
       -c:v libx264 -preset ultrafast -tune zerolatency \
       -g 30 -keyint_min 30 -sc_threshold 0 \
       -c:a aac -b:a 128k \
       -f flv rtmp://localhost:1935/live/stream
```

Parameters:
- `-preset ultrafast`: Fastest encoding
- `-tune zerolatency`: Optimize for low latency
- `-g 30`: GOP size (keyframe every 30 frames)
- `-keyint_min 30`: Minimum keyframe interval
- `-sc_threshold 0`: Disable scene detection

### For best quality:

```bash
ffmpeg -re -stream_loop -1 -i test_video.mp4 \
       -c:v libx264 -preset medium -crf 23 \
       -c:a aac -b:a 192k \
       -f flv rtmp://localhost:1935/live/stream
```

## Quick Reference

**Generate 10s test video:**
```bash
ffmpeg -f lavfi -i testsrc=duration=10:size=1280x720:rate=30 -f lavfi -i sine=frequency=1000:duration=10 -c:v libx264 -preset ultrafast -c:a aac test.mp4
```

**Stream on loop:**
```bash
ffmpeg -re -stream_loop -1 -i test.mp4 -c:v copy -c:a copy -f flv rtmp://localhost:1935/live/stream
```

**Stop streaming:**
```
Ctrl+C
```

## Summary

Testing with FFmpeg is perfect for:
- ✅ Testing gateway without Android device
- ✅ Debugging streaming issues
- ✅ Continuous testing (infinite loop)
- ✅ Consistent test patterns
- ✅ Performance benchmarking
- ✅ Automated testing in CI/CD

Now you can test the complete LudicrousLink pipeline without needing an Android device!
