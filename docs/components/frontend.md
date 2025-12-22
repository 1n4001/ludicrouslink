# Frontend (React)

The frontend is a modern React application that connects to the backend via WebSocket, decodes H.264 video using the WebCodecs API, and renders it to an HTML Canvas.

## Source Structure

```
frontend/src/
├── App.tsx                         # Root component
├── App.css                         # Styles
├── main.tsx                        # Entry point
├── components/
│   ├── StreamViewer.tsx            # Main streaming component
│   └── Controls.tsx                # Connection and control buttons
└── services/
    └── H264Decoder.ts              # WebCodecs H.264 decoder wrapper
```

## Technology Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| React | 18 | UI framework |
| TypeScript | 5.6 | Type safety |
| Vite | 6 | Build tool and dev server |
| WebCodecs API | — | Hardware-accelerated H.264 decoding |

## Key Components

### `StreamViewer`

The main component that handles:

- **WebSocket connection** to the backend `/ws` endpoint.
- **Auto-URL detection** — automatically constructs the WebSocket URL from the current page origin.
- **Codec initialization** — receives SPS/PPS from the `codec-info` message and initializes the decoder.
- **Frame decoding** — passes incoming `video-frame-h264` data to the H264 decoder.
- **Metrics** — tracks and displays FPS and latency in real-time.

### `H264Decoder`

A service class wrapping the browser's `VideoDecoder` (WebCodecs API):

- **`init(sps, pps)`** — Constructs the AVC codec string from SPS data and configures the decoder.
- **`decode(data, isKeyFrame)`** — Creates `EncodedVideoChunk` instances and feeds them to the decoder.
- **Rendering** — Decoded `VideoFrame` objects are drawn directly to a `<canvas>` element.

### `Controls`

UI component providing:

- WebSocket URL input field
- Connect / Disconnect buttons
- Fullscreen toggle
- Screenshot capture

## Features

- :framed_picture: **Canvas Rendering** — Direct GPU-accelerated rendering via `drawImage`.
- :bar_chart: **Real-time Metrics** — FPS counter and latency display updated every 100ms.
- :camera: **Screenshots** — Captures the current canvas frame as a JPEG download.
- :arrows_counterclockwise: **Auto-reconnect URL** — Detects whether served from Vite dev server or production backend.

## Development Server

```bash
cd frontend
npm install
npm run dev
```

The Vite dev server runs on port `5173`. When developing locally, the frontend auto-detects this and points the WebSocket URL to `ws://localhost:8080/ws` (the Go backend).

## Production Build

```bash
cd frontend
npm run build
```

Output is placed in `frontend/dist/`, which the Gradle build system copies to `backend/public/` for serving.
