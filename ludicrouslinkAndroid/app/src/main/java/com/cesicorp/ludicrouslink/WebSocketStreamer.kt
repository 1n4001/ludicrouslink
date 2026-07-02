package com.cesicorp.ludicrouslink

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
import com.google.flatbuffers.FlatBufferBuilder
import ludicrouslink.ClientMessage
import ludicrouslink.ClientPayload
import ludicrouslink.CodecInfo
import ludicrouslink.ServerMessage
import ludicrouslink.ServerPayload
import ludicrouslink.TouchEvent
import ludicrouslink.VideoFrame
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * WebSocket H.264 streamer using OkHttp and FlatBuffers.
 * - Publishes video frames to backend via WebSocket
 * - Receives touch events from backend
 */
class WebSocketStreamer(
    private val mediaProjectionManager: MediaProjectionManager,
    private val touchCallback: (TouchEvent) -> Unit,
    private val disconnectCallback: (String) -> Unit,
    private val width: Int = 720,
    private val height: Int = 480,
    private val bitrate: Int = 1000 * 1000,
    private val fps: Int = 30,
    private val iFrameInterval: Int = 1,
    private val useHighProfile: Boolean = false
) {
    companion object {
        private const val TAG = "WebSocketStreamer"
        private const val MIME_TYPE = "video/avc"
    }

    private var mediaProjection: MediaProjection? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket keeps connection open
        .build()

    private var encoderThread: Thread? = null
    private var isStreaming = false
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            stopStream()
        }
    }

    fun prepareMediaProjection(resultCode: Int, data: Intent): Boolean {
        return try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare MediaProjection", e)
            false
        }
    }

    fun startStream(url: String): Boolean {
        if (isStreaming) return false

        isStreaming = true
        encoderThread = Thread {
            connectAndStream(url)
        }.apply { start() }

        return true
    }

    private fun connectAndStream(url: String) {
        try {
            AppLog.i(TAG, "Connecting to $url...")

            val request = Request.Builder().url(url).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    AppLog.i(TAG, "WebSocket connected")
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Handle incoming binary messages (Touch Events)
                    val buffer = ByteBuffer.wrap(bytes.toByteArray())
                    val msg = ClientMessage.getRootAsClientMessage(buffer)
                    
                    if (msg.payloadType == ClientPayload.TouchEvent) {
                        val touchEvent = msg.payload(TouchEvent()) as TouchEvent
                        touchCallback(touchEvent)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    AppLog.i(TAG, "WebSocket closing: $reason")
                    stopStream()
                    disconnectCallback("Closing: $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    AppLog.e(TAG, "WebSocket error: ${t.message}")
                    stopStream()
                    disconnectCallback("Failure: ${t.message}")
                }
            })

            // Setup Encoder
            setupEncoder()

            // Run encoder loop
            encoderLoop()

        } catch (e: Exception) {
            AppLog.e(TAG, "Stream failed: ${e.message}", e)
            stopStream()
        }
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                setInteger(MediaFormat.KEY_PROFILE,
                    if (useHighProfile) MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    else MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
                // AVCLevel31 is widely supported and enough for 720p/1080p
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
        }

        mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "LudicrousLink", width, height, 1, 0, inputSurface, null, null
        )
    }

    private fun encoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        
        while (isStreaming) {
            val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: continue

            when (outputBufferIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Ideally send CodecInfo here, but we can extract SPS/PPS from keyframes too
                    // Or retrieve specifically via format
                    val bufferForSpsPps = mediaCodec?.outputFormat?.getByteBuffer("csd-0")
                    val bufferForPps = mediaCodec?.outputFormat?.getByteBuffer("csd-1")
                    if (bufferForSpsPps != null && bufferForPps != null) {
                        // sendCodecInfo(bufferForSpsPps, bufferForPps)
                        // Actually, Android MediaCodec sends SPS/PPS in the stream for AVC
                        // But FlatBuffers protocol expects separate CodecInfo or inside Keyframe?
                        // Our parser used to extract it.
                        // We should probably just send the raw stream data?
                        // But we want to use the FlatBuffer structure.
                        // Let's send a CodecInfo message!
                        val sps = ByteArray(bufferForSpsPps.remaining())
                        bufferForSpsPps.get(sps)
                        val pps = ByteArray(bufferForPps.remaining())
                        bufferForPps.get(pps)
                        sendCodecInfo(sps, pps)
                    }
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                else -> {
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                val data = ByteArray(bufferInfo.size)
                                outputBuffer.get(data)
                                
                                sendVideoFrame(data, isKeyFrame, bufferInfo.presentationTimeUs)
                            }
                        }
                        mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            }
        }
    }

    private fun sendCodecInfo(sps: ByteArray, pps: ByteArray) {
        val builder = FlatBufferBuilder(256)
        
        val spsOffset = builder.createByteVector(sps)
        val ppsOffset = builder.createByteVector(pps)
        
        CodecInfo.startCodecInfo(builder)
        CodecInfo.addSps(builder, spsOffset)
        CodecInfo.addPps(builder, ppsOffset)
        val codecInfo = CodecInfo.endCodecInfo(builder)
        
        ServerMessage.startServerMessage(builder)
        ServerMessage.addPayloadType(builder, ServerPayload.CodecInfo)
        ServerMessage.addPayload(builder, codecInfo)
        val msg = ServerMessage.endServerMessage(builder)
        builder.finish(msg)
        
        webSocket?.send(builder.sizedByteArray().toByteString())
    }

    private fun sendVideoFrame(data: ByteArray, isKeyFrame: Boolean, timestamp: Long) {
        // FlatBuffer Builder
        val builder = FlatBufferBuilder(data.size + 128)
        
        val dataOffset = builder.createByteVector(data)
        
        VideoFrame.startVideoFrame(builder)
        VideoFrame.addData(builder, dataOffset)
        VideoFrame.addKeyFrame(builder, isKeyFrame)
        VideoFrame.addTimestamp(builder, timestamp.toULong()) // uint64 in schema, long in kotlin
        val frameOffset = VideoFrame.endVideoFrame(builder)
        
        ServerMessage.startServerMessage(builder)
        ServerMessage.addPayloadType(builder, ServerPayload.VideoFrame)
        ServerMessage.addPayload(builder, frameOffset)
        val msg = ServerMessage.endServerMessage(builder)
        builder.finish(msg)
        
        webSocket?.send(builder.sizedByteArray().toByteString())
    }

    fun stopStream() {
        isStreaming = false
        try {
            webSocket?.close(1000, "User stopped")
            encoderThread?.join(1000)
            virtualDisplay?.release()
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping stream", e)
        }
    }
    
    fun isStreaming(): Boolean = isStreaming
    fun isPrepared(): Boolean = mediaProjection != null
}
