import React, { useEffect, useRef, useState, useCallback } from 'react';
import { H264VideoDecoder } from '../services/H264Decoder';
import { TinyH264Decoder } from '../services/TinyH264Decoder';
import { Controls } from './Controls';
import * as flatbuffers from 'flatbuffers';
import { ServerMessage, ServerPayload, CodecInfo, VideoFrame } from '../proto/hstreamer';

export const StreamViewer: React.FC = () => {
    const canvasRef = useRef<HTMLCanvasElement>(null);
    const containerRef = useRef<HTMLDivElement>(null);
    const decoderRef = useRef<H264VideoDecoder | TinyH264Decoder | null>(null);
    const wsRef = useRef<WebSocket | null>(null);

    // Force software decoder for debugging
    // const isWebCodecsSupported = 'VideoDecoder' in window;
    const isWebCodecsSupported = false;

    // FPS and Latency tracking
    const frameCountRef = useRef(0);
    const lastFpsUpdateRef = useRef(Date.now());
    const lastFrameTimeRef = useRef(0);

    const [isConnected, setIsConnected] = useState(false);
    const [wsUrl, setWsUrl] = useState('ws://localhost:8080/ws');
    const [fps, setFps] = useState(0);
    const [latency, setLatency] = useState<string>('-');
    const [status, setStatus] = useState('Disconnected');
    const [controlsVisible, setControlsVisible] = useState(true);
    const [logs, setLogs] = useState<Array<{ time: string, msg: string, type: 'info' | 'error' | 'warn' }>>([]);
    const logsEndRef = useRef<HTMLDivElement>(null);
    const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    const addLog = useCallback((msg: string, type: 'info' | 'error' | 'warn' = 'info') => {
        const time = new Date().toLocaleTimeString().split(' ')[0]; // HH:MM:SS
        setLogs(prev => [...prev.slice(-49), { time, msg, type }]); // Keep last 50
    }, []);

    // Scroll to bottom of logs
    useEffect(() => {
        if (logsEndRef.current) {
            logsEndRef.current.scrollIntoView({ behavior: 'smooth' });
        }
    }, [logs]);

    // Auto-hide controls after 4 seconds of inactivity while streaming
    const resetHideTimer = useCallback(() => {
        if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
        setControlsVisible(true);
        hideTimerRef.current = setTimeout(() => {
            if (wsRef.current?.readyState === WebSocket.OPEN) {
                setControlsVisible(false);
            }
        }, 4000);
    }, []);

    const handleVideoTap = useCallback((e: React.MouseEvent | React.TouchEvent) => {
        // Don't toggle if tapping a button or input
        const target = e.target as HTMLElement;
        if (target.closest('.control-bar') || target.closest('.hud-overlay')) return;
        setControlsVisible((v: boolean) => !v);
        resetHideTimer();
    }, [resetHideTimer]);

    const connect = useCallback((url: string) => {
        if (wsRef.current) return;

        try {
            setStatus('Connecting...');
            const ws = new WebSocket(url);
            ws.binaryType = 'arraybuffer'; // Receive binary FlatBuffer messages
            wsRef.current = ws;

            ws.onopen = async () => {
                console.log('WebSocket connected');
                setIsConnected(true);
                setStatus('Connected');
            };

            ws.onmessage = async (event) => {
                try {
                    if (!(event.data instanceof ArrayBuffer)) return;

                    const buf = new flatbuffers.ByteBuffer(new Uint8Array(event.data));
                    const msg = ServerMessage.getRootAsServerMessage(buf);

                    switch (msg.payloadType()) {
                        case ServerPayload.CodecInfo: {
                            const codecInfo = msg.payload(new CodecInfo()) as CodecInfo;
                            if (!codecInfo) break;
                            const sps = codecInfo.spsArray();
                            const pps = codecInfo.ppsArray();
                            console.log('Received codec info: SPS', sps?.length, 'bytes, PPS', pps?.length, 'bytes');
                            addLog(`Codec Info: SPS=${sps?.length}B, PPS=${pps?.length}B`, 'info');

                            if (sps && pps && !decoderRef.current) {
                                if (isWebCodecsSupported && canvasRef.current) {
                                    decoderRef.current = new H264VideoDecoder(canvasRef.current);
                                    await decoderRef.current.init(sps, pps);
                                } else if (!isWebCodecsSupported && containerRef.current) {
                                    console.log('Falling back to TinyH264 decoder');
                                    const tinyDecoder = new TinyH264Decoder(containerRef.current);
                                    tinyDecoder.onLog = (msg, type) => {
                                        console.log(`[TinyH264] ${msg}`);
                                        addLog(msg, type);
                                    };
                                    await tinyDecoder.init(sps, pps);
                                    decoderRef.current = tinyDecoder;
                                }
                            }
                            break;
                        }
                        case ServerPayload.VideoFrame: {
                            const frame = msg.payload(new VideoFrame()) as VideoFrame;
                            if (!frame || !decoderRef.current) break;
                            const h264Data = frame.dataArray();
                            if (h264Data) {
                                await decoderRef.current.decode(h264Data, frame.keyFrame());
                                frameCountRef.current++;
                                lastFrameTimeRef.current = Date.now();
                            }
                            break;
                        }
                    }
                } catch (e) {
                    console.error("Error processing message", e);
                    addLog(`Error processing message: ${e}`, 'error');
                }
            };

            ws.onclose = () => {
                const reason = 'WebSocket closed';
                console.log(reason);
                addLog(reason, 'warn');
                setIsConnected(false);
                setStatus('Disconnected');
                wsRef.current = null;
                if (decoderRef.current) {
                    decoderRef.current.close();
                    decoderRef.current = null;
                }
            };

            ws.onerror = (e) => {
                console.error("WebSocket error", e);
                addLog(`WebSocket error`, 'error');
                setStatus('Error');
            }

        } catch (e) {
            console.error("Connection failed", e);
            setStatus('Connection Failed');
        }
    }, [addLog, isWebCodecsSupported]);

    const disconnect = useCallback(() => {
        if (wsRef.current) {
            wsRef.current.close();
            wsRef.current = null;
        }
    }, []);

    const toggleFullscreen = useCallback(() => {
        const container = document.querySelector('.stream-container');
        if (!document.fullscreenElement && container) {
            container.requestFullscreen();
        } else {
            document.exitFullscreen();
        }
    }, []);

    const takeScreenshot = useCallback(() => {
        if (canvasRef.current) {
            canvasRef.current.toBlob((blob) => {
                if (!blob) return;
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `hstreamer-${Date.now()}.jpg`;
                a.click();
                URL.revokeObjectURL(url);
            }, 'image/jpeg', 0.95);
        }
    }, []);

    // Metrics loop
    useEffect(() => {
        const interval = setInterval(() => {
            const now = Date.now();
            const elapsed = now - lastFpsUpdateRef.current;

            if (elapsed >= 1000) {
                const currentFps = Math.round((frameCountRef.current * 1000) / elapsed);
                setFps(currentFps);
                frameCountRef.current = 0;
                lastFpsUpdateRef.current = now;
            }

            if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN && lastFrameTimeRef.current > 0) {
                const currentLatency = now - lastFrameTimeRef.current;
                setLatency(`${currentLatency}ms`);
            } else {
                setLatency('-');
            }

        }, 100);

        return () => clearInterval(interval);
    }, []);

    // Auto-detect WebSocket URL
    useEffect(() => {
        if (window.location.protocol.startsWith('http')) {
            const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            if (window.location.port === '5173') {
                setWsUrl(`ws://${window.location.hostname}:8080/ws`);
            } else {
                setWsUrl(`${wsProtocol}//${window.location.host}/ws`);
            }
        }
    }, []);

    return (
        <div
            className={`stream-container ${controlsVisible ? '' : 'controls-hidden'}`}
            onClick={handleVideoTap}
        >
            <div className="video-wrapper" ref={containerRef}>
                {isWebCodecsSupported && (
                    <canvas ref={canvasRef} className="video-canvas" />
                )}
                {!isConnected && (
                    <div className="no-signal-overlay">
                        <div className="signal-icon" />
                        <p>No Signal</p>
                    </div>
                )}
            </div>

            {/* Debug Overlay */}
            {logs.length > 0 && (
                <div className="debug-overlay">
                    {logs.map((L, i) => (
                        <div key={i} className={`debug-entry ${L.type}`}>
                            <span style={{ opacity: 0.5 }}>[{L.time}]</span> {L.msg}
                        </div>
                    ))}
                    <div ref={logsEndRef} />
                </div>
            )}

            <Controls
                isConnected={isConnected}
                onConnect={connect}
                onDisconnect={disconnect}
                onFullscreen={toggleFullscreen}
                onScreenshot={takeScreenshot}
                wsUrl={wsUrl}
                setWsUrl={setWsUrl}
                fps={fps}
                latency={latency}
                status={status}
            />
        </div>
    );
};
