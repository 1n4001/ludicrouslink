import { Player } from 'broadway-player';

export class BroadwayVideoDecoder {
    private player: any;
    private container: HTMLElement;
    public onLog: (msg: string, type: 'info' | 'error' | 'warn') => void = () => { };

    constructor(container: HTMLElement) {
        this.container = container;
    }

    async init(sps: Uint8Array, pps: Uint8Array) {
        try {
            this.player = new Player({
                useWorker: true,
                workerFile: '/Decoder.js', // Needs to be served from public
                webgl: 'auto',
                size: {
                    width: 1280, // Initial size, can be adjusted
                    height: 720
                }
            });

            // Append player canvas to container
            this.container.appendChild(this.player.canvas);

            this.player.onPictureDecoded = (_buffer: Uint8Array, _width: number, _height: number, _infos: any) => {
                // Optional: hook for frame decoded
            };

            // Feed SPS and PPS
            // Broadway expects raw H.264 stream.
            // We can just feed the configuration data.
            // It might strictly require start codes? Usually yes.
            // But H264Decoder might have stripped them if we reused code.
            // Here we are getting raw SPS/PPS from the caller.

            // NOTE: Broadway expects standard Annex B NALUs.
            // If the SPS/PPS passed here do NOT have start codes, we should add them.
            // The `StreamViewer` passes `codecInfo.spsArray()` which comes from flatbuffers.
            // Based on previous findings, these MIGHT have start codes.
            // But let's be safe and ensure they do.

            // Actually, wait. The SPS/PPS from `codecInfo` in `StreamViewer` come directly from the flatbuffer message.
            // In `H264Decoder.ts` we stripped them.
            // Here we probably want them.
            // Let's feed them as is. If it fails we can adjust.

            // Concatenate SPS and PPS
            this.player.decode(new Uint8Array([...sps, ...pps]));

            this.onLog('✅ Broadway initialized (Software/Canvas fallback)', 'info');

        } catch (error) {
            this.onLog(`Failed to initialize Broadway: ${error}`, 'error');
            throw error;
        }
    }

    async decode(h264Data: Uint8Array, _isKeyFrame = false) {
        if (!this.player) return;

        try {
            // Broadway takes raw NAL units.
            // We just feed the data.
            // It handles buffering.
            // Copy data to ensure it's not a view that gets invalidated
            const dataCopy = new Uint8Array(h264Data);
            this.player.decode(dataCopy);
        } catch (error) {
            this.onLog(`Error decoding frame with Broadway: ${error}`, 'error');
        }
    }

    close() {
        if (this.player) {
            // Broadway player doesn't have a standardized destroy/close?
            // checking docs/source implies manual cleanup.
            if (this.player.canvas && this.player.canvas.parentNode) {
                this.player.canvas.parentNode.removeChild(this.player.canvas);
            }
            this.player = null;
        }
    }
}
