#!/bin/bash
# Setup script for Raspberry Pi Gateway v2 (RTMP Receiver)

echo "HStreamer Gateway v2 Setup"
echo "=========================="
echo ""

# Update system
echo "[1/5] Updating system packages..."
sudo apt-get update

# Install GStreamer and dependencies
echo "[2/5] Installing GStreamer..."
sudo apt-get install -y \
    gstreamer1.0-tools \
    gstreamer1.0-plugins-base \
    gstreamer1.0-plugins-good \
    gstreamer1.0-plugins-bad \
    gstreamer1.0-plugins-ugly \
    gstreamer1.0-libav \
    gstreamer1.0-rtsp \
    python3-gi \
    python3-gi-cairo \
    python3-gst-1.0 \
    gir1.2-gstreamer-1.0 \
    gir1.2-gst-plugins-base-1.0 \
    libgstreamer1.0-dev \
    libgstreamer-plugins-base1.0-dev

# Install nginx with RTMP module
echo "[3/5] Installing nginx-rtmp..."
sudo apt-get install -y \
    libnginx-mod-rtmp \
    nginx

# Configure nginx for RTMP
echo "[4/5] Configuring nginx-rtmp..."
sudo tee /etc/nginx/nginx.conf > /dev/null <<'EOF'
user www-data;
worker_processes auto;
pid /run/nginx.pid;

events {
    worker_connections 768;
}

# RTMP configuration
rtmp {
    server {
        listen 1935;
        chunk_size 4096;
        allow publish all;

        application live {
            live on;
            record off;

            # Allow playback
            allow play all;
        }
    }
}

# HTTP configuration (optional)
http {
    server {
        listen 8080;

        location /stat {
            rtmp_stat all;
            rtmp_stat_stylesheet stat.xsl;
        }

        location /stat.xsl {
            root /usr/share/nginx/html;
        }
    }
}
EOF

# Start nginx
sudo systemctl enable nginx
sudo systemctl restart nginx

# Install Python dependencies
echo "[5/5] Installing Python packages..."
pip3 install -r requirements.txt

echo ""
echo "========================================="
echo "Setup complete!"
echo "========================================="
echo ""
echo "Services running:"
echo "  - nginx-rtmp on port 1935 (receives RTMP from Android)"
echo "  - Gateway WebSocket on port 8765 (serves web clients)"
echo ""
echo "To run the gateway:"
echo "  python3 gateway_v2.py"
echo ""
echo "Or with custom options:"
echo "  python3 gateway_v2.py --rtmp-port 1935 --ws-port 8765 --name 'My Gateway'"
echo ""
echo "The gateway will:"
echo "  1. Advertise itself via mDNS for Android to discover"
echo "  2. Receive RTMP stream from Android on port 1935"
echo "  3. Process and forward to web browsers on port 8765"
echo ""
echo "Test nginx-rtmp:"
echo "  sudo systemctl status nginx"
echo "  curl http://localhost:8080/stat"
echo ""
