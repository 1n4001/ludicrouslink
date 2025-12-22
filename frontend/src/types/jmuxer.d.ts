declare module 'jmuxer' {
    interface JMuxerOptions {
        node: string | HTMLVideoElement;
        mode?: 'video' | 'audio' | 'both';
        flushingTime?: number;
        clearBuffer?: boolean;
        fps?: number;
        debug?: boolean;
        onError?: (data: any) => void;
    }

    interface FeederData {
        video?: Uint8Array;
        audio?: Uint8Array;
        duration?: number;
    }

    export default class JMuxer {
        constructor(options: JMuxerOptions);
        feed(data: FeederData): void;
        destroy(): void;
    }
}
