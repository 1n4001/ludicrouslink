# Installing HStreamer Gateway as System Service

This guide shows how to set up the gateway to start automatically on boot.

## Prerequisites

- Gateway tested and working manually
- Root/sudo access to Raspberry Pi

## Installation Steps

### 1. Edit Service File

Edit `hstreamer-gateway.service` and update:

```ini
# Change the RTSP URL to your Android device IP
ExecStart=/usr/bin/python3 /home/pi/hstreamer-gateway/gateway.py \
  --rtsp-url rtsp://YOUR_ANDROID_IP:8554/live \
  --ws-host 0.0.0.0 \
  --ws-port 8765
```

### 2. Copy Service File

```bash
sudo cp hstreamer-gateway.service /etc/systemd/system/
```

### 3. Reload Systemd

```bash
sudo systemctl daemon-reload
```

### 4. Enable Service

```bash
sudo systemctl enable hstreamer-gateway.service
```

### 5. Start Service

```bash
sudo systemctl start hstreamer-gateway.service
```

## Managing the Service

### Check Status

```bash
sudo systemctl status hstreamer-gateway.service
```

Expected output:
```
● hstreamer-gateway.service - HStreamer Gateway Service
     Loaded: loaded (/etc/systemd/system/hstreamer-gateway.service; enabled)
     Active: active (running) since ...
```

### View Logs

```bash
# Live logs
sudo journalctl -u hstreamer-gateway.service -f

# Recent logs
sudo journalctl -u hstreamer-gateway.service -n 100

# Today's logs
sudo journalctl -u hstreamer-gateway.service --since today
```

### Stop Service

```bash
sudo systemctl stop hstreamer-gateway.service
```

### Restart Service

```bash
sudo systemctl restart hstreamer-gateway.service
```

### Disable Auto-Start

```bash
sudo systemctl disable hstreamer-gateway.service
```

## Troubleshooting

### Service won't start

**Check service status:**
```bash
sudo systemctl status hstreamer-gateway.service
```

**Check logs:**
```bash
sudo journalctl -u hstreamer-gateway.service -n 50
```

**Common issues:**

1. **Python not found**: Update `ExecStart` path
2. **Permission denied**: Check file ownership
3. **Module not found**: Ensure dependencies installed
4. **RTSP connection failed**: Verify Android IP

### Service starts but crashes

**Check GStreamer:**
```bash
gst-inspect-1.0 rtspsrc
```

**Test manually:**
```bash
cd /home/pi/hstreamer-gateway
python3 gateway.py --rtsp-url rtsp://192.168.1.100:8554/live
```

**Check dependencies:**
```bash
pip3 list | grep -E 'websockets|PyGObject'
```

### Service restarts constantly

**View restart count:**
```bash
systemctl show hstreamer-gateway.service | grep NRestarts
```

**Check logs for error patterns:**
```bash
sudo journalctl -u hstreamer-gateway.service | grep -i error
```

**Increase restart delay:**
Edit service file and increase `RestartSec=30`

## Advanced Configuration

### Environment Variables

Add to service file under `[Service]`:

```ini
Environment="GST_DEBUG=2"
Environment="PYTHONUNBUFFERED=1"
Environment="WS_PORT=8765"
```

### Multiple Instances

Create separate service files:

```bash
# Copy service file
sudo cp hstreamer-gateway.service hstreamer-gateway@device1.service

# Edit and change ports
sudo nano /etc/systemd/system/hstreamer-gateway@device1.service

# Enable both
sudo systemctl enable hstreamer-gateway@device1.service
sudo systemctl enable hstreamer-gateway@device2.service
```

### Start on Network Ready

Ensure service waits for network:

```ini
[Unit]
After=network-online.target
Wants=network-online.target
```

## Monitoring

### Set Up Alerts

**Email on failure:**

Install msmtp:
```bash
sudo apt-get install msmtp msmtp-mta
```

Add to service file:
```ini
[Service]
OnFailure=failure-email@%n.service
```

### Resource Monitoring

**CPU and memory usage:**
```bash
systemctl status hstreamer-gateway.service | grep -E 'CPU|Memory'
```

**Continuous monitoring:**
```bash
watch -n 1 'systemctl status hstreamer-gateway.service | grep -E "Active|CPU|Memory"'
```

## Uninstall

```bash
# Stop service
sudo systemctl stop hstreamer-gateway.service

# Disable service
sudo systemctl disable hstreamer-gateway.service

# Remove service file
sudo rm /etc/systemd/system/hstreamer-gateway.service

# Reload systemd
sudo systemctl daemon-reload
```

## Automatic Restart on Failure

The service is configured to automatically restart on failure with these settings:

```ini
Restart=always
RestartSec=10
```

This ensures the gateway recovers from:
- Network disconnections
- Android device restarts
- Temporary errors

## Logs Rotation

Systemd journals are automatically rotated. To configure:

Edit `/etc/systemd/journald.conf`:

```ini
[Journal]
SystemMaxUse=100M
MaxRetentionSec=1week
```

Then restart:
```bash
sudo systemctl restart systemd-journald
```

## Performance Tuning

### Increase Process Priority

Add to service file:
```ini
[Service]
Nice=-10
IOSchedulingClass=realtime
IOSchedulingPriority=0
```

### CPU Affinity

Pin to specific CPU cores:
```ini
[Service]
CPUAffinity=2 3
```

## Security Considerations

The service runs with these security restrictions:

- `NoNewPrivileges=true` - Prevents privilege escalation
- `PrivateTmp=true` - Isolates /tmp
- `ProtectSystem=strict` - Read-only system directories
- `ProtectHome=true` - No access to home directories (except working dir)

To further restrict, add:

```ini
[Service]
PrivateNetwork=false
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictRealtime=true
```

## Verification Checklist

Service is properly installed when:

- [ ] Service file copied to /etc/systemd/system/
- [ ] systemctl daemon-reload executed
- [ ] Service enabled (auto-start on boot)
- [ ] Service started and running
- [ ] Status shows "active (running)"
- [ ] Logs show successful RTSP connection
- [ ] WebSocket server accessible
- [ ] Service survives reboot

Congratulations! Your gateway now runs automatically. 🎉
