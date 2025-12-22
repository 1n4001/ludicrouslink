import React from 'react';

interface ControlsProps {
    isConnected: boolean;
    onConnect: (url: string) => void;
    onDisconnect: () => void;
    onFullscreen: () => void;
    onScreenshot: () => void;
    wsUrl: string;
    setWsUrl: (url: string) => void;
    fps: number;
    latency: string;
    status: string;
}

export const Controls: React.FC<ControlsProps> = ({
    isConnected,
    onConnect,
    onDisconnect,
    onFullscreen,
    onScreenshot,
    wsUrl,
    setWsUrl,
    fps,
    latency,
    status,
}) => {
    return (
        <>
            {/* ── Floating HUD ── */}
            <div className="hud-overlay">
                <div className="hud-pill">
                    <span className={`hud-dot ${isConnected ? 'live' : status === 'Connecting...' ? 'waiting' : 'offline'}`} />
                    {isConnected ? 'LIVE' : status.toUpperCase()}
                </div>
                {isConnected && (
                    <>
                        <div className="hud-pill">{fps} FPS</div>
                        <div className="hud-pill">{latency}</div>
                    </>
                )}
            </div>

            {/* ── Bottom Control Bar ── */}
            <div className="control-bar">
                <div className="control-left">
                    {!isConnected ? (
                        <button
                            className="btn-icon btn-connect"
                            onClick={() => onConnect(wsUrl)}
                            title="Connect"
                        >
                            ▶ Connect
                        </button>
                    ) : (
                        <button
                            className="btn-icon btn-disconnect"
                            onClick={onDisconnect}
                            title="Disconnect"
                        >
                            ⏹
                        </button>
                    )}
                </div>

                <div className="control-center">
                    <input
                        type="text"
                        className="url-input"
                        value={wsUrl}
                        onChange={(e) => setWsUrl(e.target.value)}
                        placeholder="ws://host:port/ws"
                        disabled={isConnected}
                    />
                </div>

                <div className="control-right">
                    <button className="btn-icon" onClick={onScreenshot} title="Screenshot">
                        📷
                    </button>
                    <button className="btn-icon" onClick={onFullscreen} title="Fullscreen">
                        ⛶
                    </button>
                </div>
            </div>
        </>
    );
};
