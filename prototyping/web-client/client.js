/**
 * LudicrousLink Web Client
 * Connects to gateway via WebSocket and displays real-time video stream
 */

/**
 * H264VideoDecoder - WebCodecs-based H.264 decoder
 * Uses hardware-accelerated VideoDecoder API for low CPU usage
 */
class H264VideoDecoder {
    constructor(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d');
        this.decoder = null;
        this.frameCount = 0;
        this.isConfigured = false;
    }

    async init(sps, pps) {
        try {
            this.decoder = new VideoDecoder({
                output: (frame) => this.renderFrame(frame),
                error: (e) => console.error('H.264 decoder error:', e)
            });

            // Build AVCC description from SPS and PPS for WebCodecs
            const description = this.buildAVCCDescription(sps, pps);

            // Configure for H.264 with LOW LATENCY settings
            this.decoder.configure({
                codec: 'avc1.42E01E', // H.264 Baseline Profile Level 3.0
                hardwareAcceleration: 'prefer-hardware',
                optimizeForLatency: true,  // Minimize decode latency
                description: description  // AVCC box containing SPS/PPS
            });

            this.isConfigured = true;
            console.log('✅ H.264 VideoDecoder initialized (LOW LATENCY mode, hardware-accelerated)');
        } catch (error) {
            console.error('Failed to initialize H.264 decoder:', error);
            throw error;
        }
    }

    buildAVCCDescription(sps, pps) {
        // Build AVCC (avcC box) format description from SPS and PPS
        // Format: [configurationVersion, AVCProfileIndication, profile_compatibility,
        //          AVCLevelIndication, lengthSizeMinusOne, numOfSequenceParameterSets,
        //          sequenceParameterSetLength, SPS..., numOfPictureParameterSets,
        //          pictureParameterSetLength, PPS...]

        const spsLength = sps.length;
        const ppsLength = pps.length;

        // Calculate total size: 7 bytes header + 2 bytes SPS length + SPS + 1 byte num PPS + 2 bytes PPS length + PPS
        const totalSize = 7 + 2 + spsLength + 1 + 2 + ppsLength;
        const description = new Uint8Array(totalSize);
        let idx = 0;

        // AVCC header
        description[idx++] = 0x01;  // configurationVersion
        description[idx++] = sps[1]; // AVCProfileIndication (from SPS byte 1)
        description[idx++] = sps[2]; // profile_compatibility (from SPS byte 2)
        description[idx++] = sps[3]; // AVCLevelIndication (from SPS byte 3)
        description[idx++] = 0xFF;  // lengthSizeMinusOne (4 bytes - 1 = 3, with reserved bits)

        // SPS
        description[idx++] = 0xE1;  // numOfSequenceParameterSets (1, with reserved bits)
        description[idx++] = (spsLength >> 8) & 0xFF;  // SPS length high byte
        description[idx++] = spsLength & 0xFF;          // SPS length low byte
        description.set(sps, idx);
        idx += spsLength;

        // PPS
        description[idx++] = 0x01;  // numOfPictureParameterSets
        description[idx++] = (ppsLength >> 8) & 0xFF;  // PPS length high byte
        description[idx++] = ppsLength & 0xFF;          // PPS length low byte
        description.set(pps, idx);

        console.log(`Built AVCC description: ${description.length} bytes (SPS: ${spsLength}, PPS: ${ppsLength})`);
        return description;
    }

    convertByteStreamToAVCC(byteStreamData) {
        // Convert H.264 byte-stream format (0x00000001 start codes) to AVCC format (length-prefixed)
        const nalUnits = [];
        let i = 0;

        while (i < byteStreamData.length) {
            // Look for start code (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
            let startCodeLength = 0;
            if (i + 3 < byteStreamData.length &&
                byteStreamData[i] === 0 && byteStreamData[i+1] === 0 && byteStreamData[i+2] === 0 && byteStreamData[i+3] === 1) {
                startCodeLength = 4;
            } else if (i + 2 < byteStreamData.length &&
                byteStreamData[i] === 0 && byteStreamData[i+1] === 0 && byteStreamData[i+2] === 1) {
                startCodeLength = 3;
            }

            if (startCodeLength > 0) {
                // Found start code, look for next start code to find NAL unit boundary
                let nextStart = i + startCodeLength;
                let j = nextStart;

                while (j < byteStreamData.length) {
                    if (j + 3 < byteStreamData.length &&
                        byteStreamData[j] === 0 && byteStreamData[j+1] === 0 && byteStreamData[j+2] === 0 && byteStreamData[j+3] === 1) {
                        break;
                    } else if (j + 2 < byteStreamData.length &&
                        byteStreamData[j] === 0 && byteStreamData[j+1] === 0 && byteStreamData[j+2] === 1) {
                        break;
                    }
                    j++;
                }

                // Extract NAL unit (without start code)
                const nalUnit = byteStreamData.slice(nextStart, j);
                if (nalUnit.length > 0) {
                    nalUnits.push(nalUnit);
                }

                i = j;
            } else {
                i++;
            }
        }

        // Build AVCC format: 4-byte length prefix + NAL unit data
        let totalSize = 0;
        for (const nal of nalUnits) {
            totalSize += 4 + nal.length; // 4-byte length + NAL data
        }

        const avccData = new Uint8Array(totalSize);
        let offset = 0;

        for (const nal of nalUnits) {
            // Write 4-byte length prefix (big-endian)
            avccData[offset++] = (nal.length >> 24) & 0xFF;
            avccData[offset++] = (nal.length >> 16) & 0xFF;
            avccData[offset++] = (nal.length >> 8) & 0xFF;
            avccData[offset++] = nal.length & 0xFF;

            // Write NAL unit data
            avccData.set(nal, offset);
            offset += nal.length;
        }

        return avccData;
    }

    detectKeyFrame(byteStreamData) {
        // Client-side key frame detection: check for IDR NAL unit (type 5)
        try {
            let i = 0;
            while (i < byteStreamData.length - 4) {
                if (byteStreamData[i] === 0 && byteStreamData[i+1] === 0 &&
                    byteStreamData[i+2] === 0 && byteStreamData[i+3] === 1) {
                    const nalType = byteStreamData[i+4] & 0x1F;
                    if (nalType === 5) return true;  // IDR = key frame
                    i += 4;
                } else if (byteStreamData[i] === 0 && byteStreamData[i+1] === 0 &&
                           byteStreamData[i+2] === 1) {
                    const nalType = byteStreamData[i+3] & 0x1F;
                    if (nalType === 5) return true;  // IDR = key frame
                    i += 3;
                } else {
                    i++;
                }
            }
            return false;
        } catch (error) {
            return false;
        }
    }

    async decode(h264Data, isKeyFrame = false) {
        if (!this.decoder || !this.isConfigured) {
            console.warn('Decoder not ready, skipping frame');
            return;
        }

        try {
            // Client-side key frame detection as fallback
            if (!isKeyFrame) {
                isKeyFrame = this.detectKeyFrame(h264Data);
            }

            // Convert from byte-stream format to AVCC format for WebCodecs
            const avccData = this.convertByteStreamToAVCC(h264Data);

            // Log first few key frames for debugging
            if (isKeyFrame && this.frameCount < 5) {
                console.log(`Key frame #${this.frameCount}: ${h264Data.length} bytes`);
            }

            // Create EncodedVideoChunk from H.264 NAL units in AVCC format
            const chunk = new EncodedVideoChunk({
                type: isKeyFrame ? 'key' : 'delta',
                timestamp: performance.now() * 1000, // microseconds
                data: avccData
            });

            this.decoder.decode(chunk);
            this.frameCount++;
        } catch (error) {
            console.error('Error decoding H.264 frame:', error);
        }
    }

    renderFrame(videoFrame) {
        try {
            // Resize canvas to match video dimensions
            if (this.canvas.width !== videoFrame.displayWidth ||
                this.canvas.height !== videoFrame.displayHeight) {
                this.canvas.width = videoFrame.displayWidth;
                this.canvas.height = videoFrame.displayHeight;
                console.log(`Canvas resized to ${videoFrame.displayWidth}x${videoFrame.displayHeight}`);
            }

            // Draw frame to canvas
            this.ctx.drawImage(videoFrame, 0, 0);
            this.frameCount++;

            // Clean up frame
            videoFrame.close();
        } catch (error) {
            console.error('Error rendering video frame:', error);
            if (videoFrame) {
                videoFrame.close();
            }
        }
    }

    close() {
        if (this.decoder) {
            try {
                this.decoder.close();
                console.log('H.264 decoder closed');
            } catch (error) {
                console.error('Error closing decoder:', error);
            }
            this.decoder = null;
            this.isConfigured = false;
        }
    }
}

class LudicrousLinkClient {
    constructor() {
        this.ws = null;
        this.canvas = document.getElementById('videoCanvas');
        this.ctx = this.canvas.getContext('2d');
        this.isConnected = false;

        // Video decoding setup
        this.videoMode = null; // 'h264' or 'jpeg'
        this.h264Decoder = null; // WebCodecs H.264 decoder
        this.supportsWebCodecs = false;

        // Audio removed for low latency - use Bluetooth for audio output

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

    detectVideoCapabilities() {
        // Check for WebCodecs API support
        this.supportsWebCodecs = typeof VideoDecoder !== 'undefined' &&
                                  typeof EncodedVideoChunk !== 'undefined';

        // Log capabilities
        console.log('Browser video capabilities:', {
            webCodecs: this.supportsWebCodecs,
            preferredMode: this.supportsWebCodecs ? 'h264' : 'jpeg',
            fallbackToJPEG: !this.supportsWebCodecs
        });

        return this.supportsWebCodecs ? 'h264' : 'jpeg';
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

    async onOpen() {
        console.log('WebSocket connected');
        this.isConnected = true;
        this.updateStatus('Connected', 'status-connected');
        this.btnConnect.disabled = true;
        this.btnDisconnect.disabled = false;
        this.wsUrlInput.disabled = true;

        // Detect video capabilities
        const preferredMode = this.detectVideoCapabilities();
        console.log(`Requesting video mode: ${preferredMode}`);

        // Note: Gateway will send codec-info message which we'll handle in onMessage

        // Start sending keepalive pings
        this.startKeepalive();
    }

    async onMessage(event) {
        try {
            const message = JSON.parse(event.data);

            switch (message.type) {
                case 'connected':
                    console.log('Gateway acknowledged connection (audio disabled - use Bluetooth)');
                    break;

                case 'codec-info':
                    // Gateway tells us what video format it's sending
                    console.log('Received codec info:', message);
                    this.videoMode = message.video;

                    // Initialize H.264 decoder if needed
                    if (this.videoMode === 'h264' && this.supportsWebCodecs) {
                        try {
                            // Check if SPS/PPS are provided
                            if (message.sps && message.pps) {
                                // Decode SPS and PPS from base64
                                const sps = this.base64ToUint8Array(message.sps);
                                const pps = this.base64ToUint8Array(message.pps);

                                this.h264Decoder = new H264VideoDecoder(this.canvas);
                                await this.h264Decoder.init(sps, pps);
                                console.log('Using H.264 hardware-accelerated decoding with SPS/PPS');
                            } else {
                                console.warn('H.264 mode requested but SPS/PPS not provided by gateway');
                                console.warn('Falling back to JPEG mode');
                                this.videoMode = 'jpeg';
                            }
                        } catch (error) {
                            console.error('Failed to initialize H.264 decoder, falling back to JPEG:', error);
                            this.videoMode = 'jpeg';
                        }
                    } else if (this.videoMode === 'jpeg') {
                        console.log('Using JPEG fallback mode');
                    }
                    break;

                case 'video-frame-h264':
                    // H.264 encoded frame
                    if (this.h264Decoder) {
                        const h264Data = this.base64ToUint8Array(message.data);
                        const isKeyFrame = message.keyFrame || false;
                        await this.h264Decoder.decode(h264Data, isKeyFrame);
                        // Note: frameCount incremented inside H264VideoDecoder.decode()
                        this.lastFrameTime = Date.now();

                        // Hide "No Signal" overlay
                        this.noSignal.classList.add('hidden');
                    } else {
                        console.warn('Received H.264 frame but decoder not initialized');
                    }
                    break;

                case 'frame':
                case 'video-frame':
                    // JPEG encoded frame (legacy)
                    this.renderFrame(message.data);
                    this.frameCount++;
                    this.lastFrameTime = Date.now();
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

        // Cleanup video decoder
        if (this.h264Decoder) {
            this.h264Decoder.close();
            this.h264Decoder = null;
        }
        this.videoMode = null;
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

    base64ToUint8Array(base64) {
        // Decode base64 string to Uint8Array
        const binaryString = atob(base64);
        const bytes = new Uint8Array(binaryString.length);
        for (let i = 0; i < binaryString.length; i++) {
            bytes[i] = binaryString.charCodeAt(i);
        }
        return bytes;
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
            console.log('✅ OpusDecoder initialized');

            // Initialize playback timing
            this.nextPlayTime = this.audioContext.currentTime;

            console.log('Audio system initialized:', {
                sampleRate: this.sampleRate,
                channels: this.channels,
                state: this.audioContext.state,
                currentTime: this.audioContext.currentTime.toFixed(3),
                destination: this.audioContext.destination.maxChannelCount
            });

            // Resume audio context if suspended (browser autoplay policy)
            if (this.audioContext.state === 'suspended') {
                await this.audioContext.resume();
            }

            // Play a quick test beep to verify audio output is working
            this.playTestBeep();

        } catch (error) {
            console.error('Failed to initialize audio:', error);
        }
    }

    playTestBeep() {
        // Play a 440Hz beep for 0.1 seconds to test audio output
        try {
            const duration = 0.1;
            const frequency = 440; // A4 note
            const bufferSize = this.audioContext.sampleRate * duration;
            const buffer = this.audioContext.createBuffer(1, bufferSize, this.audioContext.sampleRate);
            const channelData = buffer.getChannelData(0);

            // Generate sine wave
            for (let i = 0; i < bufferSize; i++) {
                channelData[i] = Math.sin(2 * Math.PI * frequency * i / this.audioContext.sampleRate) * 0.1;
            }

            // Play it
            const source = this.audioContext.createBufferSource();
            source.buffer = buffer;
            source.connect(this.audioContext.destination);
            source.start(this.audioContext.currentTime + 0.1);

            console.log('🔊 Playing test beep at 440Hz - if you hear a beep, audio output is working!');

            source.onended = () => {
                console.log('Test beep finished playing');
            };
        } catch (error) {
            console.error('Failed to play test beep:', error);
        }
    }

    async handleAudioFrame(base64Data) {
        if (!this.audioContext || !this.opusDecoder) {
            return;
        }

        try {
            // Wait for opus decoder to be ready (in case of race condition)
            if (this.opusDecoder.ready && typeof this.opusDecoder.ready.then === 'function') {
                await this.opusDecoder.ready;
            }

            // Decode base64 to Uint8Array
            const binaryString = atob(base64Data);
            const bytes = new Uint8Array(binaryString.length);
            for (let i = 0; i < binaryString.length; i++) {
                bytes[i] = binaryString.charCodeAt(i);
            }

            // Check if decoder is in valid state
            if (!this.opusDecoder.decode && !this.opusDecoder.decodeFrame) {
                console.warn('Opus decoder not ready, skipping frame');
                return;
            }

            // Track audio frames for debugging (only log via 2-second interval)
            if (!this.audioFramesReceived) {
                this.audioFramesReceived = 0;
            }
            this.audioFramesReceived++;

            // Decode Opus packet
            let decoded;
            try {
                decoded = await this.opusDecoder.decodeFrame(bytes);
            } catch (decodeError) {
                // Log first decode error only
                if (this.audioFramesReceived === 1) {
                    console.error('Opus decode error:', decodeError);
                }
                return;
            }

            if (decoded && decoded.channelData && decoded.channelData.length > 0) {
                // Resume AudioContext if suspended (browser autoplay policy)
                if (this.audioContext.state === 'suspended') {
                    console.log('AudioContext suspended, attempting to resume...');
                    await this.audioContext.resume();
                    console.log('AudioContext state after resume:', this.audioContext.state);
                }

                // Create audio buffer
                const audioBuffer = this.audioContext.createBuffer(
                    decoded.channelData.length,  // Use actual number of channels from decoded data
                    decoded.samplesDecoded,
                    decoded.sampleRate
                );

                // Copy decoded data to audio buffer and measure audio level
                let maxAmplitude = 0;
                for (let channel = 0; channel < decoded.channelData.length; channel++) {
                    const channelData = audioBuffer.getChannelData(channel);
                    channelData.set(decoded.channelData[channel]);

                    // Measure peak amplitude for first channel
                    if (channel === 0) {
                        for (let i = 0; i < decoded.channelData[channel].length; i++) {
                            maxAmplitude = Math.max(maxAmplitude, Math.abs(decoded.channelData[channel][i]));
                        }
                    }
                }

                // Log audio levels every 2 seconds
                const now = Date.now();
                if (now - this.lastAudioLevelLog >= 2000) {
                    const dbLevel = maxAmplitude > 0 ? (20 * Math.log10(maxAmplitude)).toFixed(1) : -Infinity;
                    console.log(`🔊 Audio level: peak=${(maxAmplitude * 100).toFixed(1)}%, dB=${dbLevel} ${maxAmplitude < 0.001 ? '⚠️ SILENCE' : ''}`);
                    this.lastAudioLevelLog = now;
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
            console.error('Opus decoder state:', {
                hasDecoder: !!this.opusDecoder,
                hasDecodeFrame: this.opusDecoder ? !!this.opusDecoder.decodeFrame : false,
                ready: this.opusDecoder ? this.opusDecoder.ready : null
            });
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
            a.download = `ludicrouslink-screenshot-${Date.now()}.jpg`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }, 'image/jpeg', 0.95);
    }
}

// Initialize client when page loads
document.addEventListener('DOMContentLoaded', () => {
    const client = new LudicrousLinkClient();
    console.log('LudicrousLink Web Client initialized');
});
