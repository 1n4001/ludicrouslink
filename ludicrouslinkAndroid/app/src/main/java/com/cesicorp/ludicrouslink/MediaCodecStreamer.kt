package com.cesicorp.hstreamer

import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Native MediaCodec H.264 encoder - no RootEncoder dependency
 * Directly sends encoded H.264 to gateway via TCP socket wrapped in MPEG-TS
 */
class MediaCodecStreamer(
    private val mediaProjectionManager: MediaProjectionManager,
    private val width: Int = 720,
    private val height: Int = 480,
    private val bitrate: Int = 1000 * 1000,  // 1 Mbps
    private val fps: Int = 30,
    private val iFrameInterval: Int = 1,  // Keyframe every 1 second
    private val useHighProfile: Boolean = false
) {
    companion object {
        private const val TAG = "MediaCodecStreamer"
        private const val MIME_TYPE = "video/avc"  // H.264
        private const val VIDEO_PID = 0x0100
        private const val PMT_PID = 0x0020
    }

    private var mediaProjection: MediaProjection? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var encoderThread: Thread? = null
    private var isStreaming = false
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    // MPEG-TS continuity counters (per-PID)
    private var ccPat = 0
    private var ccPmt = 0
    private var ccVideo = 0

    // MediaProjection callback (required for Android 14+)
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            stopStream()
        }
    }

    fun prepareMediaProjection(resultCode: Int, data: Intent): Boolean {
        return try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            Log.i(TAG, "MediaProjection prepared successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare MediaProjection: ${e.message}", e)
            false
        }
    }

    fun startStream(host: String, port: Int): Boolean {
        if (isStreaming) {
            Log.w(TAG, "Already streaming")
            return false
        }

        // Start connection thread (network operations must not be on main thread)
        isStreaming = true
        encoderThread = Thread({
            connectAndStream(host, port)
        }, "EncoderThread").apply { start() }

        Log.i(TAG, "Stream starting in background thread")
        return true
    }

    private fun connectAndStream(host: String, port: Int) {
        try {
            // Connect socket (on background thread)
            AppLog.i(TAG, "Connecting to $host:$port...")
            socket = Socket()
            socket?.connect(InetSocketAddress(host, port), 5000)
            outputStream = socket?.getOutputStream()
            AppLog.i(TAG, "Connected to $host:$port")

            // Create MediaCodec encoder
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
                setInteger(MediaFormat.KEY_PROFILE,
                    if (useHighProfile) MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    else MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }

            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            AppLog.i(TAG, "Encoder started (${width}x${height} @ ${fps}fps)")

            // Register callback before creating virtual display (required for Android 14+)
            mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            // Create virtual display (screen capture → encoder surface)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "HStreamer",
                width,
                height,
                1,  // DPI (doesn't matter for encoding)
                0,  // Flags
                inputSurface,
                null,
                null
            )

            if (virtualDisplay == null) {
                AppLog.e(TAG, "Failed to create virtual display")
                isStreaming = false
                return
            }

            AppLog.i(TAG, "Screen capture active")

            // Run encoder loop on this thread
            encoderLoop()

        } catch (e: Exception) {
            AppLog.e(TAG, "Stream failed: ${e.message}", e)
            isStreaming = false
            stopStream()
        }
    }

    fun stopStream() {
        isStreaming = false

        encoderThread?.join(2000)
        encoderThread = null

        virtualDisplay?.release()
        virtualDisplay = null

        inputSurface?.release()
        inputSurface = null

        mediaCodec?.stop()
        mediaCodec?.release()
        mediaCodec = null

        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        outputStream?.close()
        outputStream = null

        socket?.close()
        socket = null

        AppLog.i(TAG, "Stream stopped")
    }

    private fun encoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        var frameCount = 0

        Log.i(TAG, "Encoder loop started")

        // Send initial PAT/PMT
        sendPatPmt()

        while (isStreaming) {
            try {
                val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: continue

                when (outputBufferIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.i(TAG, "Output format changed: ${mediaCodec?.outputFormat}")
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available yet
                    }
                    else -> {
                        if (outputBufferIndex >= 0) {
                            val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex)

                            if (outputBuffer != null && bufferInfo.size > 0) {
                                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                                // Re-send PAT/PMT before every keyframe
                                if (isKeyFrame) {
                                    sendPatPmt()
                                }

                                // Send length-prefixed raw H.264 (4 bytes length + data)
                                val data = ByteArray(bufferInfo.size)
                                outputBuffer.get(data)
                                
                                val lengthBuffer = ByteBuffer.allocate(4)
                                lengthBuffer.putInt(data.size)
                                outputStream?.write(lengthBuffer.array())
                                outputStream?.write(data)
                                outputStream?.flush()

                                frameCount++
                                if (frameCount % 30 == 0) {
                                    Log.d(TAG, "Encoded $frameCount frames")
                                }
                            }

                            mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isStreaming) {
                    AppLog.e(TAG, "Encoder error: ${e.message}", e)
                }
                break
            }
        }

        Log.i(TAG, "Encoder loop stopped (total frames: $frameCount)")
    }

    private fun sendPatPmt() {
        // No-op: Raw H.264 stream doesn't use PAT/PMT
    }


    fun isStreaming(): Boolean = isStreaming

    fun isPrepared(): Boolean = mediaProjection != null
}
