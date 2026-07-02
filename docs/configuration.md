# Configuration

The backend server is configured via command-line flags. All settings have sensible defaults.

## Command-Line Flags

| Flag | Type | Default | Description |
|------|------|---------|-------------|
| `-http` | int | `8080` | HTTP/HTTPS server port |
| `-tcp` | int | `8888` | TCP MPEG-TS stream listener port |
| `-cert` | string | (none) | Path to TLS certificate file |
| `-key` | string | (none) | Path to TLS private key file |

## Examples

### Default (HTTP)

```bash
./backend
# Listens on :8080 (HTTP) and :8888 (TCP)
```

### Custom Ports

```bash
./backend -http 9000 -tcp 9001
# Listens on :9000 (HTTP) and :9001 (TCP)
```

### HTTPS with TLS

```bash
./backend -cert server.crt -key server.key
# Listens on :8080 (HTTPS, TLS 1.2+) and :8888 (TCP)
```

### Full Configuration

```bash
./backend -http 443 -tcp 9000 -cert /etc/ssl/certs/ludicrouslink.crt -key /etc/ssl/private/ludicrouslink.key
```

## TLS Details

When both `-cert` and `-key` are provided:

- The server starts in **HTTPS** mode.
- **TLS 1.2** is the minimum supported version (`crypto/tls.VersionTLS12`).
- The server validates that the certificate and key files exist before starting.
- WebSocket connections use `wss://` instead of `ws://`.

!!! warning "Frontend URL"
    When using TLS, the frontend will automatically use `wss://` for WebSocket connections when it detects `https:` as the page protocol. If developing with the Vite dev server against an HTTPS backend, you'll need to manually set the WebSocket URL.

### Generating a Self-Signed Certificate

For local development and testing:

```bash
openssl req -x509 -newkey rsa:4096 -keyout key.pem -out cert.pem -days 365 -nodes \
    -subj "/CN=localhost"
```

Then run:

```bash
./backend -cert cert.pem -key key.pem
```

## mDNS Discovery

The backend automatically advertises itself via mDNS (Zeroconf):

- **Service type**: `_http._tcp`
- **Instance name**: `LudicrousLink Gateway`
- **Port**: Uses the value of the `-http` flag.
