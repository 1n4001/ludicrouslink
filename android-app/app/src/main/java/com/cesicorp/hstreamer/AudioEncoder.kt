package com.cesicorp.hstreamer

import android.content.Context
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

@RequiresApi(Build.VERSION_CODES.Q)
class AudioEncoder(private val context: Context) {

    private var mediaCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private val audioDataQueue = ConcurrentLinkedQueue<AudioData>()

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bitrate = 128000 // 128 kbps

    private var isRunning = false
    private var audioConfig: ByteArray? = null

    data class AudioData(
        val data: ByteArray,
        val timestamp: Long
    )

    init {
        setupEncoder()
    }

    private fun setupEncoder() {
        try {
            // Setup audio capture with AudioPlaybackCapture (Android 10+)
            val audioPlaybackConfig = AudioPlaybackCaptureConfiguration.Builder(
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjection
            ).addMatchingUsage(AudioAttributes.USAGE_MEDIA)
             .addMatchingUsage(AudioAttributes.USAGE_GAME)
             .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
             .build()

            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(audioPlaybackConfig)
                .build()

            // Setup AAC encoder
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            audioRecord?.startRecording()

            startCaptureThread()
            startEncodingThread()

            isRunning = true
            Log.i(TAG, "Audio encoder started")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up audio encoder", e)
            throw e
        }
    }

    private fun startCaptureThread() {
        Thread {
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val buffer = ByteArray(bufferSize)

            while (isRunning) {
                try {
                    val readResult = audioRecord?.read(buffer, 0, bufferSize) ?: 0

                    if (readResult > 0) {
                        feedToEncoder(buffer, readResult)
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error in capture thread", e)
                    }
                }
            }
        }.start()
    }

    private fun feedToEncoder(pcmData: ByteArray, size: Int) {
        try {
            val inputBufferIndex = mediaCodec?.dequeueInputBuffer(10000) ?: return

            if (inputBufferIndex >= 0) {
                val inputBuffer = mediaCodec?.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(pcmData, 0, size)

                mediaCodec?.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    size,
                    System.nanoTime() / 1000,
                    0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error feeding to encoder", e)
        }
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
                            extractAudioConfig(format)
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

    private fun extractAudioConfig(format: MediaFormat?) {
        format?.let {
            if (it.containsKey("csd-0")) {
                val csd0 = it.getByteBuffer("csd-0")
                audioConfig = ByteArray(csd0.remaining())
                csd0.get(audioConfig)
                Log.i(TAG, "Audio config extracted")
            }
        }
    }

    private fun processEncodedData(encodedData: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val data = ByteArray(bufferInfo.size)
        encodedData.position(bufferInfo.offset)
        encodedData.get(data)

        audioDataQueue.offer(AudioData(data, bufferInfo.presentationTimeUs))

        // Keep queue size manageable
        while (audioDataQueue.size > 100) {
            audioDataQueue.poll()
        }
    }

    fun getNextFrame(): AudioData? = audioDataQueue.poll()
    fun getAudioConfig(): ByteArray? = audioConfig
    fun getSampleRate(): Int = sampleRate

    fun stop() {
        isRunning = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            mediaCodec?.stop()
            mediaCodec?.release()
            audioDataQueue.clear()
            Log.i(TAG, "Audio encoder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
        }
    }

    companion object {
        private const val TAG = "AudioEncoder"
    }
}
