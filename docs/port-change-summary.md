# Default Port Change Summary

## What Changed

**Default HTTP/WebSocket Port:**
- **Before:** 8080 (common port, often conflicts)
- **After:** 8765 (less common, fewer conflicts)

## Why This Change?

Port 8080 is commonly used by:
- Development servers (webpack, vite, create-react-app)
- Tomcat and other application servers
- Proxy servers and caching services
- Many other tools and frameworks

Port 8765 is:
- Less commonly used
- Was the original LudicrousLink WebSocket port (maintains continuity)
- Still easy to remember
- Reduces likelihood of port conflicts

## How to Use

### Default (Port 8765)

```bash
python3 gateway.py
```

Access at: `http://gateway-ip:8765/`

### Custom Port

```bash
# Use any port you want
python3 gateway.py --http-port 9000
```

Access at: `http://gateway-ip:9000/`

### Use Port 8080 (if you prefer)

```bash
python3 gateway.py --http-port 8080
```

Access at: `http://gateway-ip:8080/`

## Updated URLs

**New defaults:**
- Web Client: `http://gateway-ip:8765/`
- WebSocket: `ws://gateway-ip:8765/ws`
- RTMP: `rtmp://gateway-ip:1935/live/stream` (unchanged)

## Files Updated

1. **gateway.py** - Default port changed to 8765
2. **index.html** - Default WebSocket URL updated
3. **client.js** - Auto-detection works with any port
4. **INTEGRATED_WEB_SERVER.md** - Documentation updated
5. **QUICK_START.md** - All examples updated

## Firewall Configuration

Update your firewall rules:

```bash
# Old
sudo ufw allow 8080/tcp

# New
sudo ufw allow 8765/tcp
```

## No Action Required If...

- You always specify `--http-port` explicitly
- You use auto-detection in the web client (it works with any port)
- You're following the new documentation

## Backward Compatibility

The port is fully configurable, so you can still use 8080 if needed:

```bash
python3 gateway.py --http-port 8080
```

All functionality remains the same - only the default changed.

## Benefits

✅ Fewer port conflicts with common development tools
✅ Maintains continuity (8765 was previous WebSocket port)
✅ Fully configurable for any preference
✅ Auto-detection works regardless of port used
