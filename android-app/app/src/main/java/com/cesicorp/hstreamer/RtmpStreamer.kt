package com.cesicorp.hstreamer

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import com.pedro.rtplibrary.rtmp.RtmpDisplay
import com.pedro.rtplibrary.view.OpenGlView

/**
 * RTMP Streamer - Pushes stream TO gateway (not hosting server)
 * Uses rtmp-rtsp-stream-client-java library
 */
class RtmpStreamer(
    private val context: Context
) {
    private var rtmpDisplay: RtmpDisplay? = null
    private var isStreaming = false

    private val width = 1280
    private val height = 720
    private val fps = 30
    private val videoBitrate = 2000 * 1024 // 2 Mbps
    private val audioBitrate = 128 * 1024 // 128 kbps
    private val sampleRate = 44100

    fun prepare(mediaProjection: MediaProjection): Boolean {
        try {
            // Create RTMP display streamer
            rtmpDisplay = RtmpDisplay(context, true, object : ConnectCheckerRtmp() {
                override fun onConnectionStartedRtmp(rtmpUrl: String) {
                    Log.i(TAG, "Connection started: $rtmpUrl")
                }

                override fun onConnectionSuccessRtmp() {
                    Log.i(TAG, "Connection successful")
                }

                override fun onConnectionFailedRtmp(reason: String) {
                    Log.e(TAG, "Connection failed: $reason")
                    isStreaming = false
                }

                override fun onNewBitrateRtmp(bitrate: Long) {
                    Log.d(TAG, "Bitrate: $bitrate")
                }

                override fun onDisconnectRtmp() {
                    Log.i(TAG, "Disconnected")
                    isStreaming = false
                }

                override fun onAuthErrorRtmp() {
                    Log.e(TAG, "Auth error")
                }

                override fun onAuthSuccessRtmp() {
                    Log.i(TAG, "Auth success")
                }
            })

            // Prepare video and audio
            val prepared = rtmpDisplay?.prepareVideo(
                width,
                height,
                fps,
                videoBitrate,
                0, // rotation
                320 // dpi
            ) == true && rtmpDisplay?.prepareAudio(
                sampleRate,
                true, // stereo
                audioBitrate
            ) == true

            if (prepared) {
                // Set MediaProjection for internal audio capture
                rtmpDisplay?.setMediaProjection(mediaProjection)
                Log.i(TAG, "Streamer prepared: ${width}x${height} @ ${fps}fps")
            }

            return prepared
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare streamer", e)
            return false
        }
    }

    fun startStream(gatewayUrl: String): Boolean {
        try {
            if (!isStreaming && rtmpDisplay?.isStreaming != true) {
                // Start streaming to gateway
                // URL format: rtmp://gateway-ip:1935/live/stream
                val success = rtmpDisplay?.startStream(gatewayUrl) == true

                if (success) {
                    isStreaming = true
                    Log.i(TAG, "Started streaming to: $gatewayUrl")
                }

                return success
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start stream", e)
            return false
        }
    }

    fun stopStream() {
        try {
            rtmpDisplay?.stopStream()
            isStreaming = false
            Log.i(TAG, "Stream stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop stream", e)
        }
    }

    fun release() {
        try {
            stopStream()
            rtmpDisplay?.release()
            rtmpDisplay = null
            Log.i(TAG, "Streamer released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release streamer", e)
        }
    }

    fun isStreaming(): Boolean = isStreaming

    fun getBitrate(): Long = rtmpDisplay?.bitrate ?: 0

    fun getStreamWidth(): Int = width
    fun getStreamHeight(): Int = height
    fun getStreamFps(): Int = fps

    /**
     * Abstract connect checker for RTMP callbacks
     */
    abstract class ConnectCheckerRtmp : com.pedro.rtmp.utils.ConnectCheckerRtmp {
        override fun onConnectionStartedRtmp(rtmpUrl: String) {}
        override fun onConnectionSuccessRtmp() {}
        override fun onConnectionFailedRtmp(reason: String) {}
        override fun onNewBitrateRtmp(bitrate: Long) {}
        override fun onDisconnectRtmp() {}
        override fun onAuthErrorRtmp() {}
        override fun onAuthSuccessRtmp() {}
    }

    companion object {
        private const val TAG = "RtmpStreamer"
    }
}
