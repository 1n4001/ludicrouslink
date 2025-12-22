# Architecture

## System Overview

HStreamer consists of three main components that work together to deliver a real-time screen streaming experience.

```mermaid
graph LR
    subgraph Android ["Android Device"]
        Screen["Screen Capture<br/>H.264"] -- "TCP Stream (MPEG-TS)" --> MPEGTS
    end

    subgraph Backend ["Gateway Server (Go)"]
        MPEGTS["MPEG-TS Parser"] -- "Extract NALUs" --> Hub
        Hub["WebSocket Hub"]
        HTTPServer["HTTP Server"]
    end

    subgraph Frontend ["Web Client (React)"]
        HTTPServer -. "HTTP (Serve App)" .-> React["React App"]
        Hub -- "WebSocket (H.264 Frames)" --> Decoder["WebCodecs API"]
        Decoder -- "Render" --> Canvas["Canvas"]
    end
```

## Data Flow

The streaming pipeline is designed for minimal latency:

```mermaid
sequenceDiagram
    participant A as Android App
    participant B as Go Backend
    participant C as Browser Client

    A->>A: MediaCodec encodes screen as H.264
    A->>A: MPEG-TS muxes H.264 NALUs
    A->>B: TCP stream (MPEG-TS packets)
    B->>B: Parse PAT → PMT → Video PID
    B->>B: Extract PES → H.264 NAL units
    B->>B: Cache SPS/PPS for new clients
    B->>C: WebSocket JSON (base64 H.264)
    C->>C: WebCodecs VideoDecoder
    C->>C: Render to Canvas
```

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| **TCP** (not UDP) | Simplicity and reliability on local networks. Android `MediaCodec` feeds directly into a TCP socket. |
| **MPEG-TS** container | Industry-standard for H.264 transport. Provides PID-based stream multiplexing. |
| **Go backend** | Excellent concurrency primitives (goroutines/channels) map naturally to the hub-and-spoke pattern. |
| **WebCodecs API** | Hardware-accelerated H.264 decoding in the browser, much lower latency than MSE or WASM decoders. |
| **Base64 over WebSocket** | WebSocket text frames with JSON allow structured metadata (frame type, codec info) alongside the payload. |

## Component Responsibilities

| Component | Language | Role |
|-----------|----------|------|
| `hstreamerAndroid` | Kotlin | Captures screen via `MediaProjection`, encodes H.264, muxes to MPEG-TS, sends over TCP |
| `backend` | Go | Receives TCP stream, parses MPEG-TS, broadcasts H.264 frames via WebSocket, serves frontend |
| `frontend` | React/TS | Connects via WebSocket, decodes H.264 with WebCodecs, renders to Canvas |
