# HStreamer

**Low-Latency Android Screen Streaming**

[![HStreamer CI](https://github.com/1n4001/hstreamer/actions/workflows/ci.yml/badge.svg)](https://github.com/1n4001/hstreamer/actions/workflows/ci.yml)

---

HStreamer is a complete system for real-time screen streaming from Android devices to web browsers over a local network. It captures the screen on Android, encodes it as H.264 via MPEG-TS, and streams it over TCP to a Go backend, which then broadcasts individual H.264 frames to connected browser clients over WebSocket.

## Key Features

- :zap: **Low Latency** — Direct H.264 NAL unit forwarding, no re-encoding.
- :rocket: **High Performance** — Go backend efficiently handles multiple concurrent clients.
- :globe_with_meridians: **Modern Web UI** — React-based interface with real-time FPS and latency metrics.
- :satellite: **Auto-Discovery** — mDNS (Zeroconf) for automatic gateway detection.
- :lock: **TLS Support** — Optional HTTPS with TLS 1.2+ enforcement.
- :package: **Cross-Platform** — Backend builds for Linux, Windows, and macOS (x64 & ARM).

## Project Structure

```
hstreamer/
├── hstreamerAndroid/     # Android screen capture app (Kotlin)
├── backend/              # Gateway server (Go)
├── frontend/             # Web client (React + Vite + TypeScript)
├── docs/                 # This documentation (MkDocs)
├── .github/workflows/    # CI/CD pipeline
├── build.gradle.kts      # Root Gradle build configuration
├── settings.gradle.kts   # Gradle project definitions
├── mkdocs.yml            # Documentation configuration
└── pyproject.toml        # Python/docs dependencies
```

## Quick Links

| Topic | Description |
|-------|-------------|
| [Getting Started](getting-started.md) | Prerequisites and first run |
| [Architecture](architecture.md) | System design and data flow |
| [Android App](components/android.md) | Screen capture and streaming |
| [Backend](components/backend.md) | Go gateway server |
| [Frontend](components/frontend.md) | React web client |
| [Build System](build-system.md) | Gradle monorepo setup |
| [CI/CD](cicd.md) | GitHub Actions pipeline |
| [Configuration](configuration.md) | CLI flags, TLS, and ports |
| [Development](development.md) | Local development workflow |
