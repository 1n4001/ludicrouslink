declare module 'yuv-canvas' {
    export interface YUVCanvasOptions {
        webGL?: boolean;
    }

    export interface FrameSink {
        drawFrame(frame: any): void;
        clear(): void;
    }

    const YUVCanvas: {
        attach(canvas: HTMLCanvasElement, options?: YUVCanvasOptions): FrameSink;
    };
    export default YUVCanvas;
}

declare module 'yuv-buffer' {
    export interface YUVFormat {
        width: number;
        height: number;
        chromaWidth: number;
        chromaHeight: number;
        cropLeft?: number;
        cropTop?: number;
        cropWidth?: number;
        cropHeight?: number;
        displayWidth?: number;
        displayHeight?: number;
    }

    export interface YUVPlane {
        stride: number;
        data: Uint8Array;
        offset: number;
    }

    export interface YUVFrame {
        format: YUVFormat;
        y: YUVPlane;
        u: YUVPlane;
        v: YUVPlane;
    }

    export function format(fields: YUVFormat): YUVFormat;
    export function frame(format: YUVFormat, y: YUVPlane, u: YUVPlane, v: YUVPlane): YUVFrame;
    export function lumaPlane(format: YUVFormat, source: Uint8Array, stride: number, offset: number): YUVPlane;
    export function chromaPlane(format: YUVFormat, source: Uint8Array, stride: number, offset: number): YUVPlane;
}
