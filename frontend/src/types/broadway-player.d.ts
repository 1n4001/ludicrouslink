declare module 'broadway-player' {
    export class Player {
        constructor(options: {
            useWorker?: boolean;
            workerFile?: string;
            webgl?: boolean | 'auto';
            size?: { width: number; height: number };
            reuseMemory?: boolean;
        });

        canvas: HTMLCanvasElement;
        domNode: HTMLCanvasElement;

        decode(data: Uint8Array): void;

        onPictureDecoded: (buffer: Uint8Array, width: number, height: number, infos: any) => void;
        onRenderFrameComplete: (info: any) => void;
    }

    export default { Player };
}
