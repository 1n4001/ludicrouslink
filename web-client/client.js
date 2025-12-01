/**
 * HStreamer Web Client
 * Connects to gateway via WebSocket and displays real-time video stream
 */

class HStreamerClient {
    constructor() {
        this.ws = null;
        this.canvas = document.getElementById('videoCanvas');
        this.ctx = this.canvas.getContext('2d');
        this.isConnected = false;

        // Audio setup
        this.audioContext = null;
        this.opusDecoder = null;
        this.nextPlayTime = 0;  // Track when next audio should play
        this.sampleRate = 48000;
        this.channels = 2;

        // Performance metrics
        this.frameCount = 0;
        this.lastFpsUpdate = Date.now();
        this.lastFrameTime = Date.now();

        // UI elements
        this.btnConnect = document.getElementById('btnConnect');
        this.btnDisconnect = document.getElementById('btnDisconnect');
        this.btnFullscreen = document.getElementById('btnFullscreen');
        this.btnScreenshot = document.getElementById('btnScreenshot');
        this.wsUrlInput = document.getElementById('wsUrl');
        this.statusText = document.getElementById('statusText');
        this.fpsText = document.getElementById('fpsText');
        this.latencyText = document.getElementById('latencyText');
        this.noSignal = document.getElementById('noSignal');

        this.setupEventListeners();
        this.autoDetectWebSocketUrl();
        this.startMetricsUpdate();
    }

    autoDetectWebSocketUrl() {
        // If page is served from the gateway, auto-configure WebSocket URL
        if (window.location.protocol.startsWith('http')) {
            const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            const wsUrl = `${wsProtocol}//${window.location.host}/ws`;

            // Only update if it's still the default localhost value
            if (this.wsUrlInput.value.includes('localhost')) {
                this.wsUrlInput.value = wsUrl;
                console.log('Auto-detected WebSocket URL:', wsUrl);
            }
        }
    }

    setupEventListeners() {
        this.btnConnect.addEventListener('click', () => this.connect());
        this.btnDisconnect.addEventListener('click', () => this.disconnect());
        this.btnFullscreen.addEventListener('click', () => this.toggleFullscreen());
        this.btnScreenshot.addEventListener('click', () => this.takeScreenshot());

        // Allow Enter key to connect
        this.wsUrlInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter' && !this.isConnected) {
                this.connect();
            }
        });
    }

    connect() {
        const url = this.wsUrlInput.value.trim();

        if (!url) {
            alert('Please enter a WebSocket URL');
            return;
        }

        if (this.isConnected) {
            return;
        }

        this.updateStatus('Connecting...', 'status-connecting');
        this.btnConnect.disabled = true;

        try {
            this.ws = new WebSocket(url);

            this.ws.onopen = () => this.onOpen();
            this.ws.onmessage = (event) => this.onMessage(event);
            this.ws.onerror = (error) => this.onError(error);
            this.ws.onclose = () => this.onClose();

        } catch (error) {
            console.error('Connection error:', error);
            this.updateStatus('Connection Failed', 'status-disconnected');
            this.btnConnect.disabled = false;
        }
    }

    disconnect() {
        if (this.ws) {
            this.ws.close();
        }
    }

    onOpen() {
        console.log('WebSocket connected');
        this.isConnected = true;
        this.updateStatus('Connected', 'status-connected');
        this.btnConnect.disabled = true;
        this.btnDisconnect.disabled = false;
        this.wsUrlInput.disabled = true;

        // Start sending keepalive pings
        this.startKeepalive();
    }

    async onMessage(event) {
        try {
            const message = JSON.parse(event.data);

            switch (message.type) {
                case 'connected':
                    console.log('Gateway acknowledged connection');
                    // Initialize audio on first connection
                    await this.initAudio();
                    break;

                case 'frame':
                case 'video-frame':
                    this.renderFrame(message.data);
                    this.frameCount++;
                    this.lastFrameTime = Date.now();
                    break;

                case 'audio-frame':
                    await this.handleAudioFrame(message.data);
                    break;

                case 'pong':
                    // Keepalive response
                    break;

                default:
                    console.warn('Unknown message type:', message.type);
            }
        } catch (error) {
            console.error('Error processing message:', error);
        }
    }

    onError(error) {
        console.error('WebSocket error:', error);
        this.updateStatus('Error', 'status-disconnected');
    }

    onClose() {
        console.log('WebSocket closed');
        this.isConnected = false;
        this.updateStatus('Disconnected', 'status-disconnected');
        this.btnConnect.disabled = false;
        this.btnDisconnect.disabled = true;
        this.wsUrlInput.disabled = false;
        this.noSignal.classList.remove('hidden');

        if (this.keepaliveInterval) {
            clearInterval(this.keepaliveInterval);
        }

        // Cleanup audio resources
        if (this.audioContext) {
            this.audioContext.close();
            this.audioContext = null;
        }
        if (this.opusDecoder) {
            this.opusDecoder.free();
            this.opusDecoder = null;
        }
        this.nextPlayTime = 0;
    }

    renderFrame(base64Data) {
        const img = new Image();

        img.onload = () => {
            // Adjust canvas size to match image
            if (this.canvas.width !== img.width || this.canvas.height !== img.height) {
                this.canvas.width = img.width;
                this.canvas.height = img.height;
            }

            // Draw frame
            this.ctx.drawImage(img, 0, 0);

            // Hide "No Signal" overlay
            this.noSignal.classList.add('hidden');
        };

        img.onerror = () => {
            console.error('Error loading frame image');
        };

        // Decode base64 and create image
        img.src = 'data:image/jpeg;base64,' + base64Data;
    }

    startKeepalive() {
        this.keepaliveInterval = setInterval(() => {
            if (this.ws && this.ws.readyState === WebSocket.OPEN) {
                this.ws.send(JSON.stringify({ type: 'ping' }));
            }
        }, 5000);
    }

    startMetricsUpdate() {
        setInterval(() => {
            const now = Date.now();
            const elapsed = now - this.lastFpsUpdate;

            if (elapsed >= 1000) {
                const fps = Math.round((this.frameCount * 1000) / elapsed);
                this.fpsText.textContent = fps;

                this.frameCount = 0;
                this.lastFpsUpdate = now;
            }

            // Calculate latency (time since last frame)
            if (this.isConnected) {
                const latency = now - this.lastFrameTime;
                this.latencyText.textContent = latency + ' ms';
            } else {
                this.latencyText.textContent = '-';
            }
        }, 100);
    }

    updateStatus(text, className) {
        this.statusText.textContent = text;
        this.statusText.className = 'status-value ' + className;
    }

    toggleFullscreen() {
        const container = document.querySelector('.container');

        if (!document.fullscreenElement) {
            container.requestFullscreen().then(() => {
                container.classList.add('fullscreen');
            }).catch(err => {
                console.error('Error entering fullscreen:', err);
            });
        } else {
            document.exitFullscreen().then(() => {
                container.classList.remove('fullscreen');
            });
        }
    }

    async initAudio() {
        if (this.audioContext) {
            return; // Already initialized
        }

        try {
            // Create audio context
            this.audioContext = new (window.AudioContext || window.webkitAudioContext)({
                sampleRate: this.sampleRate
            });

            // Initialize Opus decoder
            // The opus-decoder library exports to window["opus-decoder"].OpusDecoder
            const OpusDecoderClass = window["opus-decoder"]?.OpusDecoder || window.OpusDecoder;
            if (!OpusDecoderClass) {
                throw new Error("OpusDecoder library not loaded");
            }
            this.opusDecoder = new OpusDecoderClass({
                sampleRate: this.sampleRate,
                channels: this.channels
            });

            await this.opusDecoder.ready;

            // Initialize playback timing
            this.nextPlayTime = this.audioContext.currentTime;

            console.log('Audio system initialized:', {
                sampleRate: this.sampleRate,
                channels: this.channels,
                state: this.audioContext.state
            });

            // Resume audio context if suspended (browser autoplay policy)
            if (this.audioContext.state === 'suspended') {
                await this.audioContext.resume();
            }

        } catch (error) {
            console.error('Failed to initialize audio:', error);
        }
    }

    async handleAudioFrame(base64Data) {
        if (!this.audioContext || !this.opusDecoder) {
            return;
        }

        try {
            // Decode base64 to Uint8Array
            const binaryString = atob(base64Data);
            const bytes = new Uint8Array(binaryString.length);
            for (let i = 0; i < binaryString.length; i++) {
                bytes[i] = binaryString.charCodeAt(i);
            }

            // Decode Opus packet
            const decoded = await this.opusDecoder.decodeFrame(bytes);

            if (decoded && decoded.channelData && decoded.channelData.length > 0) {
                // Create audio buffer
                const audioBuffer = this.audioContext.createBuffer(
                    decoded.channelData.length,  // Use actual number of channels from decoded data
                    decoded.samplesDecoded,
                    decoded.sampleRate
                );

                // Copy decoded data to audio buffer
                for (let channel = 0; channel < decoded.channelData.length; channel++) {
                    const channelData = audioBuffer.getChannelData(channel);
                    channelData.set(decoded.channelData[channel]);
                }

                // Schedule audio playback
                const source = this.audioContext.createBufferSource();
                source.buffer = audioBuffer;
                source.connect(this.audioContext.destination);

                // If we're falling behind, reset to current time
                const currentTime = this.audioContext.currentTime;
                if (this.nextPlayTime < currentTime) {
                    this.nextPlayTime = currentTime;
                }

                // Schedule this buffer to play at the next available time
                source.start(this.nextPlayTime);

                // Calculate duration and update next play time
                const duration = decoded.samplesDecoded / decoded.sampleRate;
                this.nextPlayTime += duration;
            }

        } catch (error) {
            console.error('Error handling audio frame:', error);
        }
    }

    takeScreenshot() {
        if (!this.canvas.width || !this.canvas.height) {
            alert('No video frame available to capture');
            return;
        }

        // Create download link
        this.canvas.toBlob((blob) => {
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `hstreamer-screenshot-${Date.now()}.jpg`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }, 'image/jpeg', 0.95);
    }
}

// Initialize client when page loads
document.addEventListener('DOMContentLoaded', () => {
    const client = new HStreamerClient();
    console.log('HStreamer Web Client initialized');
});
