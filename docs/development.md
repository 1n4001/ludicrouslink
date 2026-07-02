# Development

This guide covers local development workflows for each component.

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Java (JDK) | 17+ | `java -version` |
| Go | 1.22+ | `go version` |
| Node.js | 20+ | `node --version` |
| npm | 10+ | `npm --version` |

## Frontend Development

The frontend runs a Vite dev server with hot module replacement:

```bash
cd frontend
npm install    # First time only
npm run dev    # Starts dev server on :5173
```

!!! tip "Backend Required"
    The frontend dev server only serves the UI. You still need the Go backend running to receive streams and handle WebSocket connections.

The frontend auto-detects when running on Vite's port `5173` and points its WebSocket connection to `ws://localhost:8080/ws`.

### Useful Commands

| Command | Description |
|---------|-------------|
| `npm run dev` | Start dev server with HMR |
| `npm run build` | Production build → `dist/` |
| `npm run preview` | Preview production build locally |
| `npm run lint` | Run ESLint |

## Backend Development

```bash
cd backend
go mod tidy    # Sync dependencies
go run .       # Start the server
```

The server expects a `./public` directory for static frontend files. During development, you can either:

1. Run the frontend dev server separately (recommended), or
2. Manually copy `frontend/dist/` to `backend/public/`.

### Useful Commands

| Command | Description |
|---------|-------------|
| `go run .` | Run the server directly |
| `go build .` | Compile the binary |
| `go test ./...` | Run all tests |
| `go vet ./...` | Static analysis |

## Android Development

Open `ludicrouslinkAndroid/` in Android Studio for the best development experience.

Alternatively, use Gradle from the root:

```bash
# Build debug APK
./gradlew :ludicrouslinkAndroid:app:assembleDebug

# Install on connected device
./gradlew :ludicrouslinkAndroid:app:installDebug
```

## Full Stack (Gradle)

To build and run everything with a single command:

```bash
./gradlew runBackend
```

This builds the frontend, copies it into the backend, and starts the server.

## Documentation

The documentation is built with MkDocs and the Material theme.

### Setup

```bash
python -m venv .venv

# Windows
.venv\Scripts\activate

# Linux/macOS
source .venv/bin/activate

pip install -e ".[docs]"
```

### Serve Locally

```bash
mkdocs serve
```

Opens a live-reloading documentation server at `http://localhost:8000`.

### Build Static Site

```bash
mkdocs build
```

Output is placed in `site/`.
