package com.cesicorp.hstreamer

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class StreamingService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var rtmpStreamer: RtmpStreamer? = null
    private var gatewayUrl: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val url = intent.getStringExtra(EXTRA_GATEWAY_URL)
                if (data != null && url != null) {
                    startStreaming(data, url)
                }
            }
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startStreaming(data: Intent, url: String) {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))

        try {
            // Initialize MediaProjection
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, data)

            // Initialize RTMP streamer
            rtmpStreamer = RtmpStreamer(applicationContext)

            // Prepare streamer
            if (rtmpStreamer?.prepare(mediaProjection!!) == true) {
                gatewayUrl = url

                // Start streaming to gateway
                if (rtmpStreamer?.startStream(url) == true) {
                    updateNotification("Streaming to gateway")
                    Log.i(TAG, "Streaming started to: $url")
                } else {
                    Log.e(TAG, "Failed to start stream")
                    stopSelf()
                }
            } else {
                Log.e(TAG, "Failed to prepare streamer")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting streaming", e)
            stopSelf()
        }
    }

    private fun stopStreaming() {
        try {
            rtmpStreamer?.release()
            mediaProjection?.stop()

            rtmpStreamer = null
            mediaProjection = null
            gatewayUrl = null

            Log.i(TAG, "Streaming stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping streaming", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen streaming service"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HStreamer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }

    companion object {
        private const val TAG = "StreamingService"
        private const val CHANNEL_ID = "streaming_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.cesicorp.hstreamer.ACTION_START"
        const val ACTION_STOP = "com.cesicorp.hstreamer.ACTION_STOP"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_GATEWAY_URL = "gateway_url"
    }
}
