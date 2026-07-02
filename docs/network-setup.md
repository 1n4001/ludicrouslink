# LudicrousLink Network Setup Guide

Comprehensive guide for configuring your network for optimal LudicrousLink performance.

## Network Topology

```
                    ┌──────────────┐
                    │  Wi-Fi Router│
                    │  192.168.1.1 │
                    └───────┬──────┘
                            │
           ┌────────────────┼────────────────┐
           │                │                │
    ┌──────▼─────┐   ┌──────▼──────┐  ┌─────▼────┐
    │  Android   │   │ Raspberry Pi│  │  Laptop  │
    │ (RTSP Src) │   │  (Gateway)  │  │ (Client) │
    │.100:8554   │   │ .101:8765   │  │          │
    └────────────┘   └─────────────┘  └──────────┘
```

## Network Requirements

### Bandwidth Requirements

| Resolution | Bitrate | Recommended Bandwidth |
|------------|---------|----------------------|
| 720p @ 30fps | 2 Mbps | 5 Mbps |
| 1080p @ 30fps | 4 Mbps | 10 Mbps |
| 1080p @ 60fps | 8 Mbps | 20 Mbps |

**Formula:** Recommended Bandwidth = Bitrate × 2.5 (for overhead)

### Latency Requirements

- **Local Network Latency**: < 5ms between devices
- **Wi-Fi Signal Strength**: > -60 dBm
- **Packet Loss**: < 0.1%

## Network Configuration

### 1. Static IP Addresses (Recommended)

Assign static IPs to ensure consistent connections:

#### Android Device

1. Go to **Settings → Wi-Fi**
2. Long-press your network → **Modify Network**
3. Advanced Options → IP Settings → **Static**
4. Set:
   - IP Address: `192.168.1.100`
   - Gateway: `192.168.1.1`
   - DNS 1: `8.8.8.8`

#### Raspberry Pi

Edit `/etc/dhcpcd.conf`:

```bash
interface wlan0
static ip_address=192.168.1.101/24
static routers=192.168.1.1
static domain_name_servers=8.8.8.8 8.8.4.4

interface eth0
static ip_address=192.168.1.101/24
static routers=192.168.1.1
static domain_name_servers=8.8.8.8
```

Restart networking:
```bash
sudo systemctl restart dhcpcd
```

### 2. Router Configuration

#### Port Forwarding (Optional - for external access)

| Device | Internal IP | Internal Port | External Port | Protocol |
|--------|-------------|---------------|---------------|----------|
| Android | 192.168.1.100 | 8554 | 18554 | TCP |
| Pi Gateway | 192.168.1.101 | 8765 | 18765 | TCP |

**Security Warning:** Only enable port forwarding if you need external access. Use VPN instead when possible.

#### QoS (Quality of Service)

Configure QoS on your router to prioritize streaming traffic:

1. Log into router admin panel (usually `192.168.1.1`)
2. Find QoS settings
3. Add rules:
   - **High Priority**: Android IP (192.168.1.100), Port 8554
   - **High Priority**: Pi IP (192.168.1.101), Port 8765
   - **Protocol**: TCP

#### Firewall Rules

Ensure these ports are open:

**Android:**
- Inbound: TCP 8554 (RTSP)

**Raspberry Pi:**
- Inbound: TCP 8765 (WebSocket)
- Outbound: TCP 8554 (to Android)

**Client Computer:**
- Outbound: TCP 8765 (to Pi)

### 3. Firewall Configuration

#### Android Firewall

Most Android devices don't have built-in firewalls. If you use a firewall app:
- Allow LudicrousLink app
- Allow TCP port 8554

#### Raspberry Pi Firewall (UFW)

```bash
# Install UFW
sudo apt-get install ufw

# Allow SSH (important!)
sudo ufw allow 22/tcp

# Allow gateway WebSocket
sudo ufw allow 8765/tcp

# Allow outbound to Android RTSP
sudo ufw allow out to 192.168.1.100 port 8554 proto tcp

# Enable firewall
sudo ufw enable

# Check status
sudo ufw status verbose
```

#### Windows Client Firewall

Usually no configuration needed for outbound WebSocket connections.

If issues:
```powershell
# Allow outbound on port 8765
New-NetFirewallRule -DisplayName "LudicrousLink Client" -Direction Outbound -LocalPort 8765 -Protocol TCP -Action Allow
```

## Network Testing

### 1. Check Connectivity

**Ping test from Pi to Android:**
```bash
ping 192.168.1.100
```

Expected: < 5ms latency, 0% packet loss

**Ping test from Android to Pi:**
```bash
# Use Termux or similar
ping 192.168.1.101
```

### 2. Port Availability

**Check if Android RTSP port is open:**
```bash
# From Raspberry Pi
nc -zv 192.168.1.100 8554
```

Expected: `Connection to 192.168.1.100 8554 port [tcp/*] succeeded!`

**Check if Pi WebSocket port is open:**
```bash
# From client computer
telnet 192.168.1.101 8765
```

### 3. Bandwidth Test

**Between Android and Pi:**
```bash
# On Raspberry Pi (server)
iperf3 -s

# On Android (using Termux)
iperf3 -c 192.168.1.101
```

Expected: > 20 Mbps for 720p streaming

### 4. RTSP Stream Test

**Test with VLC (best way to verify Android stream):**
```bash
vlc rtsp://192.168.1.100:8554/live
```

Or using ffplay:
```bash
ffplay -rtsp_transport tcp rtsp://192.168.1.100:8554/live
```

### 5. WebSocket Test

**Test WebSocket connectivity:**
```bash
# Using wscat (install: npm install -g wscat)
wscat -c ws://192.168.1.101:8765
```

Expected: Connection opens, can send/receive JSON messages

## Network Optimization

### Wi-Fi Optimization

#### 1. Use 5GHz Band

Configure your router to use 5GHz:
- Less interference
- Higher bandwidth
- Better for streaming

#### 2. Channel Selection

Choose least congested channel:

**Scan for channels:**
```bash
# On Linux
sudo apt-get install wavemon
wavemon
```

**Best channels:**
- 2.4GHz: 1, 6, 11 (non-overlapping)
- 5GHz: 36, 40, 44, 48 (lower frequency, better range)

#### 3. Wi-Fi Settings

Optimal router settings:
- **Mode**: AC or AX (Wi-Fi 5/6)
- **Channel Width**: 80MHz (5GHz)
- **Security**: WPA2 or WPA3
- **Beacon Interval**: 100ms
- **RTS Threshold**: 2347
- **Fragmentation Threshold**: 2346

### 4. Disable Power Saving

**Android:**
Settings → Battery → Battery Optimization → LudicrousLink → Don't Optimize

**Raspberry Pi:**
```bash
# Disable Wi-Fi power management
sudo iw wlan0 set power_save off

# Make permanent - add to /etc/rc.local
echo "iw wlan0 set power_save off" | sudo tee -a /etc/rc.local
```

### 5. Use Ethernet for Gateway

**Best practice:** Connect Raspberry Pi to router via Ethernet cable

Benefits:
- Lower latency
- More stable connection
- Frees Wi-Fi bandwidth for Android

Configuration:
```bash
# Check connection
ip addr show eth0

# Test speed
ethtool eth0
```

## Advanced Network Scenarios

### Scenario 1: Multiple Android Devices

Assign sequential IPs:
- Android 1: 192.168.1.100:8554
- Android 2: 192.168.1.102:8554
- Android 3: 192.168.1.103:8554

Run multiple gateway instances:
```bash
python3 gateway.py --rtsp-url rtsp://192.168.1.100:8554/live --ws-port 8765
python3 gateway.py --rtsp-url rtsp://192.168.1.102:8554/live --ws-port 8766
python3 gateway.py --rtsp-url rtsp://192.168.1.103:8554/live --ws-port 8767
```

### Scenario 2: Separate Network Segments

If Android and Pi are on different subnets, configure routing:

```bash
# On Raspberry Pi
sudo ip route add 192.168.2.0/24 via 192.168.1.1

# Or make permanent in /etc/network/interfaces
```

### Scenario 3: VPN Access

Set up VPN on Raspberry Pi for remote access:

**Using WireGuard:**
```bash
# Install WireGuard
sudo apt-get install wireguard

# Configure server
# ... (detailed WireGuard setup)

# Connect from remote client via VPN
# Then access: ws://[VPN-IP]:8765
```

## Monitoring Network Performance

### Real-Time Monitoring

**On Raspberry Pi:**
```bash
# Monitor bandwidth usage
sudo apt-get install iftop
sudo iftop -i wlan0

# Monitor connections
sudo netstat -tuln | grep -E '8554|8765'

# Monitor WebSocket connections
sudo ss -tunlp | grep 8765
```

**On Android:**
Use network monitoring app like "Network Monitor"

### Performance Metrics

Ideal metrics:
- **Ping latency**: < 5ms
- **Jitter**: < 2ms
- **Packet loss**: 0%
- **Throughput**: > 20 Mbps
- **Gateway CPU**: < 50%
- **Android CPU**: < 30%

## Troubleshooting Network Issues

### Issue: High Latency (>500ms)

**Diagnosis:**
```bash
# Check ping
ping -c 100 192.168.1.100

# Check Wi-Fi signal
iwconfig wlan0

# Check interference
sudo iw wlan0 scan
```

**Solutions:**
1. Move devices closer to router
2. Switch to 5GHz band
3. Change Wi-Fi channel
4. Use Ethernet for Pi

### Issue: Connection Drops

**Diagnosis:**
```bash
# Monitor connection stability
ping -i 0.2 192.168.1.100

# Check system logs
dmesg | grep -i wifi
```

**Solutions:**
1. Disable power saving
2. Update Wi-Fi drivers
3. Check router logs for issues
4. Reduce network congestion

### Issue: Low Throughput

**Diagnosis:**
```bash
# Test bandwidth
iperf3 -c 192.168.1.100
```

**Solutions:**
1. Upgrade to AC/AX router
2. Use 5GHz band
3. Enable QoS
4. Reduce interference

## Network Security Best Practices

1. **Change default router password**
2. **Enable WPA3 encryption** (or WPA2 minimum)
3. **Disable WPS**
4. **Use guest network** for IoT devices
5. **Enable router firewall**
6. **Keep firmware updated**
7. **Use strong Wi-Fi password** (20+ characters)
8. **Disable remote administration** on router
9. **Enable MAC filtering** (optional, reduced convenience)
10. **Monitor connected devices** regularly

## Summary Checklist

Network setup is complete when:

- [ ] All devices have static IPs assigned
- [ ] Firewall rules configured and tested
- [ ] Port connectivity verified (8554, 8765)
- [ ] Bandwidth test shows >20 Mbps
- [ ] Ping latency <5ms with 0% loss
- [ ] RTSP stream works in VLC
- [ ] WebSocket connection succeeds
- [ ] Wi-Fi power saving disabled
- [ ] QoS configured (optional)
- [ ] 5GHz Wi-Fi enabled (recommended)

Your network is now optimized for LudicrousLink! 🚀
