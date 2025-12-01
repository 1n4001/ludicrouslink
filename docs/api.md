# HStreamer API Documentation

Technical reference for developers extending or integrating with HStreamer.

## Table of Contents

1. [Android API](#android-api)
2. [Gateway API](#gateway-api)
3. [WebSocket Protocol](#websocket-protocol)
4. [RTSP Stream Format](#rtsp-stream-format)

---

## Android API

### ScreenEncoder

Handles screen capture and H.264 encoding.

#### Constructor

```kotlin
ScreenEncoder(
    mediaProjection: MediaProjection,
    context: Context
)
```

**Parameters:**
- `mediaProjection`: Android MediaProjection for screen capture
- `context`: Application context

#### Methods

##### `getNextFrame(): VideoData?`

Returns the next available encoded video frame.

**Returns:** `VideoData?` - Video frame data or null if queue is empty

**VideoData Structure:**
```kotlin
data class VideoData(
    val data: ByteArray,      // H.264 NAL units
    val isKeyFrame: Boolean,  // True if IDR frame
    val timestamp: Long       // PTS in microseconds
)
```

##### `getSPS(): ByteArray?`

Returns H.264 Sequence Parameter Set.

**Returns:** SPS data or null if not yet available

##### `getPPS(): ByteArray?`

Returns H.264 Picture Parameter Set.

**Returns:** PPS data or null if not yet available

##### `getWidth(): Int`

Returns encoded video width in pixels.

##### `getHeight(): Int`

Returns encoded video height in pixels.

##### `getFps(): Int`

Returns target frames per second.

##### `stop()`

Stops encoding and releases resources.

#### Configuration

Edit these properties in `ScreenEncoder.kt`:

```kotlin
private var width = 1280          // Video width
private var height = 720          // Video height
private val fps = 30              // Target FPS
private val bitrate = 2000000     // Bitrate in bps
```

#### Example Usage

```kotlin
val screenEncoder = ScreenEncoder(mediaProjection, context)

// Get SPS/PPS for stream initialization
val sps = screenEncoder.getSPS()
val pps = screenEncoder.getPPS()

// Continuously read frames
while (streaming) {
    screenEncoder.getNextFrame()?.let { frame ->
        // Process frame
        processFrame(frame.data, frame.isKeyFrame, frame.timestamp)
    }
}

// Cleanup
screenEncoder.stop()
```

---

### AudioEncoder

Handles internal audio capture and AAC encoding.

#### Constructor

```kotlin
@RequiresApi(Build.VERSION_CODES.Q)
AudioEncoder(context: Context)
```

**Parameters:**
- `context`: Application context

**Requirements:**
- Android 10+ (API 29) for AudioPlaybackCapture

#### Methods

##### `getNextFrame(): AudioData?`

Returns the next available encoded audio frame.

**Returns:** `AudioData?` - Audio frame data or null if queue is empty

**AudioData Structure:**
```kotlin
data class AudioData(
    val data: ByteArray,    // AAC frame data
    val timestamp: Long     // PTS in microseconds
)
```

##### `getAudioConfig(): ByteArray?`

Returns AAC AudioSpecificConfig.

**Returns:** Config data or null if not yet available

##### `getSampleRate(): Int`

Returns audio sample rate.

**Returns:** Sample rate in Hz (default: 44100)

##### `stop()`

Stops encoding and releases resources.

#### Configuration

Edit these properties in `AudioEncoder.kt`:

```kotlin
private val sampleRate = 44100          // Sample rate
private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
private val bitrate = 128000            // Bitrate in bps
```

#### Example Usage

```kotlin
val audioEncoder = AudioEncoder(context)

// Get audio config
val config = audioEncoder.getAudioConfig()

// Continuously read frames
while (streaming) {
    audioEncoder.getNextFrame()?.let { frame ->
        // Process frame
        processAudio(frame.data, frame.timestamp)
    }
}

// Cleanup
audioEncoder.stop()
```

---

### RtspServer

Implements RTSP server protocol.

#### Constructor

```kotlin
RtspServer(
    port: Int,
    screenEncoder: ScreenEncoder,
    audioEncoder: AudioEncoder
)
```

**Parameters:**
- `port`: TCP port to listen on (default: 8554)
- `screenEncoder`: Video encoder instance
- `audioEncoder`: Audio encoder instance

#### Methods

##### `start()`

Starts RTSP server and begins accepting clients.

##### `stop()`

Stops server and disconnects all clients.

#### Supported RTSP Methods

- `OPTIONS` - Query supported methods
- `DESCRIBE` - Get SDP description
- `SETUP` - Setup transport parameters
- `PLAY` - Start streaming
- `TEARDOWN` - Stop streaming and close session

#### SDP Format

```sdp
v=0
o=- 0 0 IN IP4 127.0.0.1
s=HStreamer
t=0 0
m=video 0 RTP/AVP 96
a=rtpmap:96 H264/90000
a=fmtp:96 packetization-mode=1;profile-level-id=42001f;sprop-parameter-sets=<SPS>,<PPS>
a=control:track0
m=audio 0 RTP/AVP 97
a=rtpmap:97 mpeg4-generic/44100/2
a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1210
a=control:track1
```

#### Example Usage

```kotlin
val rtspServer = RtspServer(8554, screenEncoder, audioEncoder)
rtspServer.start()

// Server now accepts connections at rtsp://[device-ip]:8554/live

// When done
rtspServer.stop()
```

---

### StreamingService

Foreground service managing streaming lifecycle.

#### Intent Actions

##### `ACTION_START`

Starts streaming service.

**Extra Parameters:**
- `EXTRA_RESULT_DATA` (Intent): MediaProjection result data

**Example:**
```kotlin
val intent = Intent(context, StreamingService::class.java).apply {
    action = StreamingService.ACTION_START
    putExtra(StreamingService.EXTRA_RESULT_DATA, mediaProjectionData)
}
startForegroundService(intent)
```

##### `ACTION_STOP`

Stops streaming service.

**Example:**
```kotlin
val intent = Intent(context, StreamingService::class.java).apply {
    action = StreamingService.ACTION_STOP
}
startService(intent)
```

#### Configuration

```kotlin
companion object {
    private const val PORT = 8554  // RTSP server port
}
```

---

## Gateway API

### GStreamerRTSPClient

Python class for receiving and processing RTSP stream.

#### Constructor

```python
GStreamerRTSPClient(rtsp_url: str, frame_queue: Queue)
```

**Parameters:**
- `rtsp_url`: RTSP stream URL (e.g., "rtsp://192.168.1.100:8554/live")
- `frame_queue`: Queue for outputting processed frames

#### Methods

##### `start()`

Starts GStreamer pipeline in background thread.

##### `stop()`

Stops pipeline and releases resources.

#### Pipeline Architecture

```
rtspsrc
  → rtph264depay
  → avdec_h264
  → videoconvert
  → videoscale
  → jpegenc
  → appsink
```

**Pipeline Elements:**

- **rtspsrc**: RTSP client
  - `location`: RTSP URL
  - `latency`: Buffering latency in ms (0 = minimal)

- **rtph264depay**: RTP H.264 depayloader
  - Extracts H.264 from RTP packets

- **avdec_h264**: H.264 decoder (libavcodec)
  - Hardware-accelerated when available

- **videoconvert**: Color space conversion
  - Converts to RGB

- **videoscale**: Resolution scaling
  - Scales to target resolution (1280x720)

- **jpegenc**: JPEG encoder
  - `quality`: 0-100 (default: 85)

- **appsink**: Application sink
  - `emit-signals`: true
  - `max-buffers`: 1 (drop old frames)
  - `drop`: true (drop if buffer full)

#### Example Usage

```python
from queue import Queue

frame_queue = Queue(maxsize=10)
client = GStreamerRTSPClient("rtsp://192.168.1.100:8554/live", frame_queue)

client.start()

# Process frames
while True:
    if not frame_queue.empty():
        jpeg_data = frame_queue.get()
        # Process frame...

client.stop()
```

#### Customizing Pipeline

Edit `_run_pipeline()` in `gateway.py`:

```python
# Example: Change resolution
pipeline_str = (
    f"rtspsrc location={self.rtsp_url} latency=0 ! "
    "rtph264depay ! "
    "avdec_h264 ! "
    "videoconvert ! "
    "video/x-raw,format=RGB ! "
    "videoscale ! "
    "video/x-raw,width=640,height=480 ! "  # Changed resolution
    "jpegenc quality=60 ! "  # Changed quality
    "appsink name=sink emit-signals=true max-buffers=1 drop=true"
)
```

---

### WebSocketServer

Python class for serving frames to web clients via WebSocket.

#### Constructor

```python
WebSocketServer(host: str, port: int, frame_queue: Queue)
```

**Parameters:**
- `host`: Bind address (e.g., "0.0.0.0")
- `port`: TCP port (e.g., 8765)
- `frame_queue`: Queue containing JPEG frames

#### Methods

##### `async start()`

Starts WebSocket server (async).

**Example:**
```python
server = WebSocketServer("0.0.0.0", 8765, frame_queue)
await server.start()
```

##### `_handle_client(websocket, path)`

Internal handler for client connections.

##### `_broadcast_frames()`

Internal coroutine broadcasting frames to all clients.

#### Client Management

```python
# Connected clients stored in set
self.clients: Set[websockets.WebSocketServerProtocol]

# Add client
self.clients.add(websocket)

# Remove client
self.clients.remove(websocket)

# Broadcast to all
for client in self.clients:
    await client.send(message)
```

---

### Gateway

Main coordinator class.

#### Constructor

```python
Gateway(
    rtsp_url: str,
    ws_host: str = '0.0.0.0',
    ws_port: int = 8765
)
```

**Parameters:**
- `rtsp_url`: RTSP source URL
- `ws_host`: WebSocket bind address
- `ws_port`: WebSocket port

#### Methods

##### `start()`

Starts both RTSP client and WebSocket server.

##### `stop()`

Stops all services.

#### Example Usage

```python
gateway = Gateway(
    rtsp_url="rtsp://192.168.1.100:8554/live",
    ws_host="0.0.0.0",
    ws_port=8765
)

try:
    gateway.start()
except KeyboardInterrupt:
    gateway.stop()
```

---

## WebSocket Protocol

Communication protocol between gateway and web clients.

### Message Format

All messages are JSON-encoded strings.

#### Client → Gateway

##### Ping Message

```json
{
  "type": "ping"
}
```

**Purpose:** Keepalive heartbeat

**Response:** Pong message

#### Gateway → Client

##### Connected Message

```json
{
  "type": "connected",
  "message": "Connected to HStreamer gateway"
}
```

**Sent:** Immediately after WebSocket connection established

##### Frame Message

```json
{
  "type": "frame",
  "data": "<base64-encoded-jpeg>",
  "format": "jpeg"
}
```

**Sent:** For each video frame

**Fields:**
- `type`: Always "frame"
- `data`: Base64-encoded JPEG image
- `format`: Image format (currently always "jpeg")

**Frame Rate:** Determined by source stream FPS

##### Pong Message

```json
{
  "type": "pong"
}
```

**Sent:** In response to ping

### Connection Lifecycle

```
Client                          Gateway
  │                               │
  ├──── WebSocket Connect ───────>│
  │<────── Connected msg ──────────┤
  │                               │
  │<────── Frame msg ──────────────┤
  │<────── Frame msg ──────────────┤
  │<────── Frame msg ──────────────┤
  │                               │
  ├──── Ping msg ────────────────>│
  │<────── Pong msg ───────────────┤
  │                               │
  │<────── Frame msg ──────────────┤
  ...                            ...
  │                               │
  ├──── Close ────────────────────>│
  │<────── Close ──────────────────┤
  │                               │
```

### Error Handling

WebSocket errors are logged but don't send error messages to clients. Clients detect errors via connection close events.

---

## RTSP Stream Format

### URL Format

```
rtsp://[host]:[port]/[path]
```

**Default:** `rtsp://[android-ip]:8554/live`

### Transport

- **Protocol:** RTSP over TCP
- **Port:** 8554 (default)
- **Streaming:** RTP over RTSP (interleaved)

### Video Track

- **Codec:** H.264 (AVC)
- **Profile:** Baseline
- **Level:** 3.1
- **RTP Payload Type:** 96
- **Packetization:** Mode 1 (non-interleaved)
- **Clock Rate:** 90000 Hz

**fmtp:**
```
packetization-mode=1;profile-level-id=42001f;sprop-parameter-sets=<SPS>,<PPS>
```

### Audio Track

- **Codec:** AAC-LC
- **RTP Payload Type:** 97
- **Sample Rate:** 44100 Hz
- **Channels:** 2 (stereo)
- **Mode:** AAC-hbr (high bitrate)

**fmtp:**
```
streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1210
```

### RTSP Session Example

#### 1. OPTIONS

**Request:**
```
OPTIONS rtsp://192.168.1.100:8554/live RTSP/1.0
CSeq: 1
```

**Response:**
```
RTSP/1.0 200 OK
CSeq: 1
Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN
```

#### 2. DESCRIBE

**Request:**
```
DESCRIBE rtsp://192.168.1.100:8554/live RTSP/1.0
CSeq: 2
Accept: application/sdp
```

**Response:**
```
RTSP/1.0 200 OK
CSeq: 2
Content-Type: application/sdp
Content-Length: 456

v=0
o=- 0 0 IN IP4 127.0.0.1
s=HStreamer
t=0 0
m=video 0 RTP/AVP 96
a=rtpmap:96 H264/90000
a=fmtp:96 packetization-mode=1;profile-level-id=42001f;sprop-parameter-sets=Z0IAH6kAUAW7EAA=,aM48gA==
a=control:track0
```

#### 3. SETUP

**Request:**
```
SETUP rtsp://192.168.1.100:8554/live/track0 RTSP/1.0
CSeq: 3
Transport: RTP/AVP;unicast;client_port=5000-5001
```

**Response:**
```
RTSP/1.0 200 OK
CSeq: 3
Session: 1234567890
Transport: RTP/AVP;unicast;client_port=5000-5001;server_port=5000-5001
```

#### 4. PLAY

**Request:**
```
PLAY rtsp://192.168.1.100:8554/live RTSP/1.0
CSeq: 4
Session: 1234567890
```

**Response:**
```
RTSP/1.0 200 OK
CSeq: 4
Session: 1234567890
RTP-Info: url=rtsp://192.168.1.100:8554/live/track0;seq=0;rtptime=0
```

---

## Integration Examples

### Custom Web Client

```javascript
class CustomClient {
    constructor(wsUrl) {
        this.ws = new WebSocket(wsUrl);
        this.ws.onmessage = (event) => this.handleMessage(event);
    }

    handleMessage(event) {
        const msg = JSON.parse(event.data);

        switch (msg.type) {
            case 'frame':
                this.displayFrame(msg.data);
                break;
            case 'connected':
                console.log('Connected to gateway');
                break;
        }
    }

    displayFrame(base64Data) {
        const img = new Image();
        img.src = 'data:image/jpeg;base64,' + base64Data;
        img.onload = () => {
            // Draw to canvas or display element
        };
    }
}

const client = new CustomClient('ws://192.168.1.101:8765');
```

### Recording Stream

```python
# Add to gateway.py pipeline
pipeline_str = (
    f"rtspsrc location={self.rtsp_url} latency=0 ! "
    "rtph264depay ! "
    "tee name=t ! "
    "queue ! avdec_h264 ! videoconvert ! jpegenc ! appsink ... "
    "t. ! queue ! h264parse ! mp4mux ! filesink location=recording.mp4"
)
```

### Multiple Stream Sources

```python
# Create multiple gateway instances
gateways = []

for i, rtsp_url in enumerate(rtsp_urls):
    gateway = Gateway(
        rtsp_url=rtsp_url,
        ws_port=8765 + i
    )
    gateways.append(gateway)
    gateway.start()
```

---

## Performance Tuning

### Latency Optimization

**Android:**
- Reduce I-frame interval
- Lower resolution
- Increase FPS

**Gateway:**
- Set `latency=0` in rtspsrc
- Use `drop=true` in appsink
- Minimize queue sizes

**Network:**
- Use 5GHz Wi-Fi
- Enable QoS
- Reduce buffering

### Quality Optimization

**Android:**
- Increase bitrate
- Increase resolution
- Use Main/High profile

**Gateway:**
- Increase JPEG quality
- Increase output resolution
- Disable frame dropping

---

## Extending the System

### Adding Authentication

**Gateway WebSocket:**
```python
async def _handle_client(self, websocket, path):
    # Check for auth token
    token = websocket.request_headers.get('Authorization')
    if not self.verify_token(token):
        await websocket.close(1008, "Unauthorized")
        return

    # Continue with normal handling...
```

### Adding SSL/TLS

**Gateway WebSocket:**
```python
import ssl

ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ssl_context.load_cert_chain('cert.pem', 'key.pem')

async with websockets.serve(
    self._handle_client,
    self.host,
    self.port,
    ssl=ssl_context
):
    # ...
```

### Custom Frame Processing

**Gateway:**
```python
def process_frame(self, jpeg_data):
    # Add watermark, filter, etc.
    from PIL import Image
    import io

    img = Image.open(io.BytesIO(jpeg_data))
    # Process image...
    output = io.BytesIO()
    img.save(output, format='JPEG')
    return output.getvalue()
```

---

For more examples and use cases, see the main README.md and example implementations in the repository.
