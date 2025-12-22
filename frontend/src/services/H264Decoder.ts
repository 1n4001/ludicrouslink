export class H264VideoDecoder {
    private canvas: HTMLCanvasElement;
    private ctx: CanvasRenderingContext2D;
    private decoder: VideoDecoder | null = null;
    private frameCount: number = 0;
    private isConfigured: boolean = false;
    private waitingForKeyFrame: boolean = true;
    public onLog: (msg: string, type: 'info' | 'error' | 'warn') => void = () => { };

    constructor(canvas: HTMLCanvasElement) {
        this.canvas = canvas;
        const ctx = canvas.getContext('2d');
        if (!ctx) throw new Error("Could not get 2D context");
        this.ctx = ctx;
    }

    async init(sps: Uint8Array, pps: Uint8Array) {
        try {
            this.decoder = new VideoDecoder({
                output: (frame) => this.renderFrame(frame),
                error: (e) => this.log(`H.264 decoder error: ${e}`, 'error')
            });

            // Strip start code (0x00000001 or 0x000001) if present
            sps = this.stripStartCode(sps);
            pps = this.stripStartCode(pps);

            // Build AVCC description from SPS and PPS
            const description = this.buildAVCCDescription(sps, pps);

            // Construct codec string from SPS (avc1.PPCCLL)
            const profile = sps[1].toString(16).padStart(2, '0').toUpperCase();
            const compatibility = sps[2].toString(16).padStart(2, '0').toUpperCase();
            const level = sps[3].toString(16).padStart(2, '0').toUpperCase();
            const codecString = `avc1.${profile}${compatibility}${level}`;

            console.log(`🔧 Configuring decoder with codec: ${codecString}`);

            // Configure for H.264
            this.decoder.configure({
                codec: codecString,
                hardwareAcceleration: 'prefer-hardware',
                optimizeForLatency: true,
                description: description
            });

            this.isConfigured = true;
            this.waitingForKeyFrame = true;
            this.log('✅ H.264 VideoDecoder initialized', 'info');
        } catch (error) {
            this.log(`Failed to initialize H.264 decoder: ${error}`, 'error');
            throw error;
        }
    }

    private log(msg: string, type: 'info' | 'error' | 'warn') {
        // Also log to console for devtools availability
        if (type === 'error') console.error(msg);
        else if (type === 'warn') console.warn(msg);
        else console.log(msg);

        this.onLog(msg, type);
    }

    private buildAVCCDescription(sps: Uint8Array, pps: Uint8Array): Uint8Array {
        const spsLength = sps.length;
        const ppsLength = pps.length;
        const totalSize = 7 + 2 + spsLength + 1 + 2 + ppsLength;
        const description = new Uint8Array(totalSize);
        let idx = 0;

        // AVCC header
        description[idx++] = 0x01; // configurationVersion
        description[idx++] = sps[1]; // AVCProfileIndication
        description[idx++] = sps[2]; // profile_compatibility
        description[idx++] = sps[3]; // AVCLevelIndication
        description[idx++] = 0xFF; // lengthSizeMinusOne

        // SPS
        description[idx++] = 0xE1; // numOfSequenceParameterSets
        description[idx++] = (spsLength >> 8) & 0xFF;
        description[idx++] = spsLength & 0xFF;
        description.set(sps, idx);
        idx += spsLength;

        // PPS
        description[idx++] = 0x01; // numOfPictureParameterSets
        description[idx++] = (ppsLength >> 8) & 0xFF;
        description[idx++] = ppsLength & 0xFF;
        description.set(pps, idx);

        return description;
    }

    private stripStartCode(data: Uint8Array): Uint8Array {
        if (data.length > 4 && data[0] === 0 && data[1] === 0 && data[2] === 0 && data[3] === 1) {
            return data.slice(4);
        } else if (data.length > 3 && data[0] === 0 && data[1] === 0 && data[2] === 1) {
            return data.slice(3);
        }
        return data;
    }

    private convertByteStreamToAVCC(byteStreamData: Uint8Array): Uint8Array {
        const nalUnits: Uint8Array[] = [];
        let i = 0;

        while (i < byteStreamData.length) {
            let startCodeLength = 0;
            if (i + 3 < byteStreamData.length &&
                byteStreamData[i] === 0 && byteStreamData[i + 1] === 0 && byteStreamData[i + 2] === 0 && byteStreamData[i + 3] === 1) {
                startCodeLength = 4;
            } else if (i + 2 < byteStreamData.length &&
                byteStreamData[i] === 0 && byteStreamData[i + 1] === 0 && byteStreamData[i + 2] === 1) {
                startCodeLength = 3;
            }

            if (startCodeLength > 0) {
                let nextStart = i + startCodeLength;
                let j = nextStart;

                while (j < byteStreamData.length) {
                    if (j + 3 < byteStreamData.length &&
                        byteStreamData[j] === 0 && byteStreamData[j + 1] === 0 && byteStreamData[j + 2] === 0 && byteStreamData[j + 3] === 1) {
                        break;
                    } else if (j + 2 < byteStreamData.length &&
                        byteStreamData[j] === 0 && byteStreamData[j + 1] === 0 && byteStreamData[j + 2] === 1) {
                        break;
                    }
                    j++;
                }

                if (j > nextStart) {
                    nalUnits.push(byteStreamData.slice(nextStart, j));
                }
                i = j;
            } else {
                i++;
            }
        }

        let totalSize = 0;
        for (const nal of nalUnits) {
            totalSize += 4 + nal.length;
        }

        const avccData = new Uint8Array(totalSize);
        let offset = 0;
        for (const nal of nalUnits) {
            avccData[offset++] = (nal.length >> 24) & 0xFF;
            avccData[offset++] = (nal.length >> 16) & 0xFF;
            avccData[offset++] = (nal.length >> 8) & 0xFF;
            avccData[offset++] = nal.length & 0xFF;
            avccData.set(nal, offset);
            offset += nal.length;
        }

        return avccData;
    }

    detectKeyFrame(byteStreamData: Uint8Array): boolean {
        let i = 0;
        while (i < byteStreamData.length - 4) {
            if (byteStreamData[i] === 0 && byteStreamData[i + 1] === 0 &&
                byteStreamData[i + 2] === 0 && byteStreamData[i + 3] === 1) {
                const nalType = byteStreamData[i + 4] & 0x1F;
                if (nalType === 5) return true;
                i += 4;
            } else if (byteStreamData[i] === 0 && byteStreamData[i + 1] === 0 &&
                byteStreamData[i + 2] === 1) {
                const nalType = byteStreamData[i + 3] & 0x1F;
                if (nalType === 5) return true;
                i += 3;
            } else {
                i++;
            }
        }
        return false;
    }

    async decode(h264Data: Uint8Array, isKeyFrame = false) {
        if (!this.decoder || !this.isConfigured) return;

        try {
            // Strictly validate keyframes (IDR) even if server hinted so
            if (isKeyFrame) {
                if (!this.detectKeyFrame(h264Data)) {
                    this.log(`⚠️ Server flagged KeyFrame but no IDR slice (NAL 5) found. Treating as Delta.`, 'warn');
                    isKeyFrame = false;
                }
            } else {
                isKeyFrame = this.detectKeyFrame(h264Data);
            }

            // After configure(), we must wait for a keyframe before decoding
            if (this.waitingForKeyFrame) {
                if (!isKeyFrame) {
                    // console.debug('⏳ Waiting for first IDR frame...');
                    return; // silently drop until first keyframe
                }
                this.waitingForKeyFrame = false;
                this.log('🎬 First verified IDR keyframe received, decoding started', 'info');
            }

            const avccData = this.convertByteStreamToAVCC(h264Data);

            const chunk = new EncodedVideoChunk({
                type: isKeyFrame ? 'key' : 'delta',
                timestamp: performance.now() * 1000,
                data: avccData
            });

            this.decoder.decode(chunk);
        } catch (error) {
            this.log(`Error decoding frame: ${error}`, 'error');
        }
    }

    private renderFrame(videoFrame: VideoFrame) {
        if (this.canvas.width !== videoFrame.displayWidth ||
            this.canvas.height !== videoFrame.displayHeight) {
            this.canvas.width = videoFrame.displayWidth;
            this.canvas.height = videoFrame.displayHeight;
        }
        this.ctx.drawImage(videoFrame, 0, 0);
        videoFrame.close();
        this.frameCount++;
    }

    close() {
        if (this.decoder) {
            this.decoder.close();
            this.decoder = null;
        }
    }
}
