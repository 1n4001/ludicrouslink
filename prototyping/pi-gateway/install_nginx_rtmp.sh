#!/bin/bash
# Install and configure nginx-rtmp for LudicrousLink Gateway

set -e  # Exit on error

echo "Installing nginx-rtmp module..."
echo "================================"

# Update package list
sudo apt-get update

# Try different installation methods
if sudo apt-get install -y libnginx-mod-rtmp 2>/dev/null; then
    echo "✓ Installed libnginx-mod-rtmp package"
elif sudo apt-get install -y nginx-extras 2>/dev/null; then
    echo "✓ Installed nginx-extras (includes RTMP module)"
else
    echo "Package manager installation failed. Building from source..."

    # Install build dependencies
    sudo apt-get install -y \
        build-essential \
        libpcre3 \
        libpcre3-dev \
        libssl-dev \
        zlib1g-dev \
        git

    # Download nginx and rtmp module
    cd /tmp
    wget http://nginx.org/download/nginx-1.24.0.tar.gz
    tar -xzf nginx-1.24.0.tar.gz
    git clone https://github.com/arut/nginx-rtmp-module.git

    # Build nginx with RTMP module
    cd nginx-1.24.0
    ./configure \
        --prefix=/usr/share/nginx \
        --sbin-path=/usr/sbin/nginx \
        --modules-path=/usr/lib/nginx/modules \
        --conf-path=/etc/nginx/nginx.conf \
        --error-log-path=/var/log/nginx/error.log \
        --http-log-path=/var/log/nginx/access.log \
        --pid-path=/run/nginx.pid \
        --lock-path=/run/lock/subsys/nginx \
        --with-http_ssl_module \
        --add-module=../nginx-rtmp-module

    make
    sudo make install

    echo "✓ Built nginx with RTMP module from source"
fi

# Check if module file exists
if [ -f "/usr/lib/nginx/modules/ngx_rtmp_module.so" ]; then
    echo "✓ RTMP module found at /usr/lib/nginx/modules/ngx_rtmp_module.so"
    MODULE_PATH="/usr/lib/nginx/modules/ngx_rtmp_module.so"
elif [ -f "/usr/lib/nginx/modules/ngx_stream_module.so" ]; then
    echo "✓ Using nginx-extras modules"
    MODULE_PATH=""  # nginx-extras loads automatically
else
    echo "⚠ RTMP module not found - checking if built-in..."
    MODULE_PATH=""
fi

# Backup existing config
if [ -f "/etc/nginx/nginx.conf" ]; then
    sudo cp /etc/nginx/nginx.conf /etc/nginx/nginx.conf.backup.$(date +%Y%m%d_%H%M%S)
    echo "✓ Backed up existing nginx config"
fi

# Write nginx configuration
sudo tee /etc/nginx/nginx.conf > /dev/null <<'EOF'
# LudicrousLink Gateway - nginx with RTMP

user www-data;
worker_processes auto;
pid /run/nginx.pid;

# Load RTMP module if needed
# Uncomment next line if module isn't loaded automatically
# load_module /usr/lib/nginx/modules/ngx_rtmp_module.so;

events {
    worker_connections 1024;
}

# RTMP configuration for receiving streams from Android
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

            # Optional: Notify when stream starts/stops
            # on_publish http://localhost:8080/on_publish;
            # on_publish_done http://localhost:8080/on_publish_done;
        }
    }
}

# HTTP configuration for stats and monitoring
http {
    sendfile on;
    tcp_nopush on;
    tcp_nodelay on;
    keepalive_timeout 65;
    types_hash_max_size 2048;

    include /etc/nginx/mime.types;
    default_type application/octet-stream;

    access_log /var/log/nginx/access.log;
    error_log /var/log/nginx/error.log;

    server {
        listen 8080;
        server_name localhost;

        # RTMP statistics page
        location /stat {
            rtmp_stat all;
            rtmp_stat_stylesheet stat.xsl;
        }

        location /stat.xsl {
            root /usr/share/nginx/html;
        }

        # Control endpoint (for stopping streams, etc.)
        location /control {
            rtmp_control all;
        }

        # Simple status page
        location / {
            return 200 "LudicrousLink Gateway RTMP Server\nStatus: Running\nRTMP Port: 1935\n";
            add_header Content-Type text/plain;
        }
    }
}
EOF

echo "✓ Created nginx configuration"

# Test nginx configuration
echo ""
echo "Testing nginx configuration..."
if sudo nginx -t; then
    echo "✓ nginx configuration is valid"
else
    echo "✗ nginx configuration has errors"
    echo "  Restoring backup..."
    sudo mv /etc/nginx/nginx.conf.backup.* /etc/nginx/nginx.conf 2>/dev/null || true
    exit 1
fi

# Enable and restart nginx
sudo systemctl enable nginx
sudo systemctl restart nginx

# Check if nginx is running
sleep 2
if sudo systemctl is-active --quiet nginx; then
    echo "✓ nginx is running"
else
    echo "✗ nginx failed to start"
    echo "  Check logs: sudo journalctl -u nginx -n 50"
    exit 1
fi

# Check if port 1935 is listening
if sudo netstat -tuln | grep -q ":1935"; then
    echo "✓ nginx-rtmp listening on port 1935"
else
    echo "⚠ Port 1935 not listening - check nginx logs"
fi

echo ""
echo "================================"
echo "nginx-rtmp installation complete!"
echo "================================"
echo ""
echo "Test RTMP server with:"
echo "  ffmpeg -re -f lavfi -i testsrc -c:v libx264 -f flv rtmp://localhost:1935/live/stream"
echo ""
echo "View statistics at:"
echo "  curl http://localhost:8080/stat"
echo ""
echo "Logs:"
echo "  sudo tail -f /var/log/nginx/error.log"
echo ""
