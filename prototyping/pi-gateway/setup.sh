#!/bin/bash
# Setup script for Raspberry Pi Gateway

echo "LudicrousLink Gateway Setup"
echo "======================="

# Update system
echo "Updating system packages..."
sudo apt-get update

# Install GStreamer and dependencies
echo "Installing GStreamer..."
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

# Install Python dependencies
echo "Installing Python packages..."
pip3 install -r requirements.txt

echo ""
echo "Setup complete!"
echo ""
echo "To run the gateway:"
echo "  python3 gateway.py --rtsp-url rtsp://[ANDROID_IP]:8554/live"
echo ""
echo "Example:"
echo "  python3 gateway.py --rtsp-url rtsp://192.168.1.100:8554/live"
