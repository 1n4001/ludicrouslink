# Getting Started

## Prerequisites

| Component | Requirement |
|-----------|-------------|
| **Android** | Android 10+ device |
| **Java** | JDK 17+ (for Gradle) |
| **Go** | 1.22+ |
| **Node.js** | 20+ (optional — Gradle can manage it) |

## Running the Server

The project is a Gradle monorepo. A single command builds the frontend, bundles it into the backend, and starts the server:

```bash
./gradlew runBackend
```

This will:

1. **Build the React frontend** — runs `npm run build` in `frontend/`.
2. **Copy assets** — copies `frontend/dist/` to `backend/public/`.
3. **Tidy Go modules** — runs `go mod tidy`.
4. **Compile the Go backend** — produces the server binary.
5. **Start the server** — listens on port `8080` (HTTP) and `8888` (TCP stream).

Once running, open **<http://localhost:8080>** in your browser.

## Running the Android App

Build and install the debug APK:

```bash
./gradlew :ludicrouslinkAndroid:app:installDebug
```

Or open `ludicrouslinkAndroid/` in Android Studio.

!!! tip "Network"
    Make sure your Android device and host machine are on the same local network. The Android app sends the MPEG-TS stream to the gateway's TCP port (default `8888`).

## First Stream

1. Start the backend: `./gradlew runBackend`
2. Open `http://localhost:8080` in your browser and click **Connect**.
3. Launch the LudicrousLink app on your Android device.
4. Grant screen capture permission when prompted.
5. The stream should appear in the browser within seconds.
