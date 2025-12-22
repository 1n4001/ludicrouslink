import { TinyH264Decoder as YumeTinyH264Decoder } from '@yume-chan/scrcpy-decoder-tinyh264';
import type { ScrcpyMediaStreamPacket } from '@yume-chan/scrcpy';

export class TinyH264Decoder {
    private container: HTMLElement;
    private decoder: YumeTinyH264Decoder;
    private writer: WritableStreamDefaultWriter<ScrcpyMediaStreamPacket>;
    public onLog: (msg: string, type: 'info' | 'error' | 'warn') => void = () => { };

    constructor(container: HTMLElement) {
        this.container = container;

        const canvas = document.createElement('canvas');
        canvas.style.width = '100%';
        canvas.style.height = '100%';
        canvas.style.backgroundColor = '#000';
        this.container.appendChild(canvas);

        this.decoder = new YumeTinyH264Decoder({ canvas });
        this.writer = this.decoder.writable.getWriter();
    }

    async init(sps: Uint8Array, pps: Uint8Array) {
        try {
            this.onLog('Initializing TinyH264 (@yume-chan)...', 'info');

            // Ensure start codes (Annex B)
            const spsWithStart = this.ensureStartCode(sps);
            const ppsWithStart = this.ensureStartCode(pps);

            // Log SPS details
            this.onLog(`SPS Bytes: ${Array.from(spsWithStart).map(b => b.toString(16).padStart(2, '0')).join(' ')}`, 'info');

            // Find Profile Indication
            // Start code can be 00 00 01 (3 bytes) or 00 00 00 01 (4 bytes).
            // SPS NAL unit type is 7.
            // Profile is the byte AFTER the NAL header.
            // Header is 1 byte.

            let profileIndex = -1;
            if (spsWithStart.length > 4 && spsWithStart[0] === 0 && spsWithStart[1] === 0 && spsWithStart[2] === 0 && spsWithStart[3] === 1) {
                // 4-byte start code.
                // spsWithStart[4] is NAL header.
                // spsWithStart[5] is profile_idc.
                profileIndex = 5;
            } else if (spsWithStart.length > 3 && spsWithStart[0] === 0 && spsWithStart[1] === 0 && spsWithStart[2] === 1) {
                // 3-byte start code.
                // spsWithStart[3] is NAL header.
                // spsWithStart[4] is profile_idc.
                profileIndex = 4;
            }

            if (profileIndex !== -1 && spsWithStart.length > profileIndex + 2) {
                const profile = spsWithStart[profileIndex];
                const level = spsWithStart[profileIndex + 2];
                this.onLog(`SPS Profile: ${profile} (Hex: ${profile.toString(16)}), Level: ${level}`, 'info');

                if (profile !== 66 && profile !== 77 && profile !== 88) {
                    this.onLog('WARNING: Profile is NOT Baseline (66). TinyH264 only supports Baseline!', 'warn');
                }
            }

            // The scrcpy configuration packet expects the raw sequence/picture parameter sets
            // concatenated. The library will parse them.
            const configData = new Uint8Array(spsWithStart.length + ppsWithStart.length);
            configData.set(spsWithStart, 0);
            configData.set(ppsWithStart, spsWithStart.length);

            await this.writer.write({
                type: 'configuration',
                data: configData
            });

            this.onLog('TinyH264 Initialized', 'info');

        } catch (error) {
            this.onLog(`Failed to initialize TinyH264: ${error}`, 'error');
            throw error;
        }
    }

    async decode(h264Data: Uint8Array, isKeyFrame = false) {
        try {
            // Ensure start code for data frames too
            const dataWithStartCode = this.ensureStartCode(h264Data);

            await this.writer.write({
                type: 'data',
                keyframe: isKeyFrame,
                data: dataWithStartCode
            });
        } catch (error) {
            this.onLog(`Decode error: ${error}`, 'error');
        }
    }

    close() {
        try {
            this.writer.releaseLock();
            this.decoder.dispose();
        } catch (e) {
            console.error(e);
        }
    }

    private ensureStartCode(data: Uint8Array): Uint8Array {
        // Check for 00 00 00 01
        if (data.length >= 4 && data[0] === 0 && data[1] === 0 && data[2] === 0 && data[3] === 1) {
            return data;
        }
        // Check for 00 00 01
        if (data.length >= 3 && data[0] === 0 && data[1] === 0 && data[2] === 1) {
            return data;
        }

        // No start code found, prepend 00 00 00 01
        const newData = new Uint8Array(data.length + 4);
        newData.set([0, 0, 0, 1], 0);
        newData.set(data, 4);
        return newData;
    }
}
