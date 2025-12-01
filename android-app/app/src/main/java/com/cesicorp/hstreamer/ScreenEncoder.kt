package com.cesicorp.hstreamer

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

class ScreenEncoder(
    private val mediaProjection: MediaProjection,
    private val context: Context
) {
    private var mediaCodec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val videoDataQueue = ConcurrentLinkedQueue<VideoData>()

    private var width = 1280
    private var height = 720
    private val fps = 30
    private val bitrate = 2000000 // 2 Mbps

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var isRunning = false

    data class VideoData(
        val data: ByteArray,
        val isKeyFrame: Boolean,
        val timestamp: Long
    )

    init {
        adjustResolution()
        setupEncoder()
    }

    private fun adjustResolution() {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val size = Point()
            windowManager.defaultDisplay.getRealSize(size)

            // Scale down to target resolution while maintaining aspect ratio
            val aspectRatio = size.x.toFloat() / size.y.toFloat()

            if (size.x > size.y) {
                // Landscape
                width = 1280
                height = (width / aspectRatio).toInt()
            } else {
                // Portrait
                height = 1280
                width = (height * aspectRatio).toInt()
            }

            // Ensure dimensions are even
            width = width and 0xFFFE.inv()
            height = height and 0xFFFE.inv()

            Log.i(TAG, "Encoding resolution: ${width}x${height}")
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting resolution", e)
        }
    }

    private fun setupEncoder() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // Keyframe every 2 seconds
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            val surface = mediaCodec?.createInputSurface()
            mediaCodec?.start()

            startVirtualDisplay(surface!!)
            startEncodingThread()

            isRunning = true
            Log.i(TAG, "Screen encoder started")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up encoder", e)
            throw e
        }
    }

    private fun startVirtualDisplay(surface: Surface) {
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "HStreamer",
            width, height,
            context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null, null
        )
    }

    private fun startEncodingThread() {
        Thread {
            val bufferInfo = MediaCodec.BufferInfo()

            while (isRunning) {
                try {
                    val encoderStatus = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: continue

                    when {
                        encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = mediaCodec?.outputFormat
                            extractSPSandPPS(format)
                        }
                        encoderStatus >= 0 -> {
                            val encodedData = mediaCodec?.getOutputBuffer(encoderStatus)

                            if (encodedData != null && bufferInfo.size > 0) {
                                processEncodedData(encodedData, bufferInfo)
                            }

                            mediaCodec?.releaseOutputBuffer(encoderStatus, false)
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error in encoding thread", e)
                    }
                }
            }
        }.start()
    }

    private fun extractSPSandPPS(format: MediaFormat?) {
        format?.let {
            if (it.containsKey("csd-0")) {
                val csd0 = it.getByteBuffer("csd-0")
                sps = ByteArray(csd0.remaining())
                csd0.get(sps)
            }
            if (it.containsKey("csd-1")) {
                val csd1 = it.getByteBuffer("csd-1")
                pps = ByteArray(csd1.remaining())
                csd1.get(pps)
            }
            Log.i(TAG, "SPS/PPS extracted")
        }
    }

    private fun processEncodedData(encodedData: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val data = ByteArray(bufferInfo.size)
        encodedData.position(bufferInfo.offset)
        encodedData.get(data)

        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

        videoDataQueue.offer(VideoData(data, isKeyFrame, bufferInfo.presentationTimeUs))

        // Keep queue size manageable
        while (videoDataQueue.size > 100) {
            videoDataQueue.poll()
        }
    }

    fun getNextFrame(): VideoData? = videoDataQueue.poll()

    fun getSPS(): ByteArray? = sps
    fun getPPS(): ByteArray? = pps
    fun getWidth(): Int = width
    fun getHeight(): Int = height
    fun getFps(): Int = fps

    fun stop() {
        isRunning = false
        try {
            virtualDisplay?.release()
            mediaCodec?.stop()
            mediaCodec?.release()
            videoDataQueue.clear()
            Log.i(TAG, "Screen encoder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
        }
    }

    companion object {
        private const val TAG = "ScreenEncoder"
    }
}
