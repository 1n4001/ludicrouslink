# Android App

The `hstreamerAndroid` module is a native Kotlin Android application responsible for screen capture and streaming.

## Source Structure

```
hstreamerAndroid/app/src/main/java/com/cesicorp/hstreamer/
├── MainActivity.kt          # UI and permission handling
├── ScreenService.kt         # Foreground service for screen capture
├── MediaCodecStreamer.kt     # H.264 encoding and MPEG-TS muxing
├── ServiceDiscovery.kt      # mDNS gateway discovery
└── Extensions.kt            # Kotlin extension utilities
```

## How It Works

### Screen Capture

The app uses Android's `MediaProjection` API to capture the screen. This requires user consent via a system dialog, after which `ScreenService` runs as a foreground service to ensure capture continues in the background.

### Video Encoding

`MediaCodecStreamer` configures a hardware `MediaCodec` encoder for H.264:

- **Codec**: `video/avc` (H.264)
- **Output**: NAL units wrapped in MPEG-TS packets
- **Transport**: Sent over a raw TCP socket to the gateway server

### Service Discovery

`ServiceDiscovery` uses Android's `NsdManager` to discover the gateway on the local network. However, in the current architecture, the gateway address is typically configured directly in the app settings.

## Requirements

- Android 10 (API 29) or higher
- Screen capture permission (granted at runtime)

## Building

=== "Gradle (from root)"

    ```bash
    ./gradlew :hstreamerAndroid:app:assembleDebug
    ```

=== "Android Studio"

    Open the `hstreamerAndroid/` directory in Android Studio and use the standard build/run workflow.

## Key Dependencies

| Dependency | Purpose |
|------------|---------|
| `MediaProjection` | Screen capture API |
| `MediaCodec` | Hardware H.264 encoding |
| `NsdManager` | mDNS service discovery |
| Jetpack Compose | Modern UI toolkit |
