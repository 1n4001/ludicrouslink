package com.cesicorp.ludicrouslink

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaCodecInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat


/**
 * Basic Screen service streaming implementation with native MediaCodec
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class ScreenService: Service() {

    enum class Quality(val width: Int, val height: Int, val bitrate: Int, val fps: Int, val iFrameInterval: Int, val useHighProfile: Boolean) {
        LOW(720, 480, 1_000_000, 30, 1, false),        // 480p @ 1 Mbps
        MEDIUM(1280, 720, 2_000_000, 30, 1, false),      // 720p @ 2 Mbps - Force Baseline
        HIGH(1920, 1080, 4_000_000, 30, 1, false)         // 1080p @ 4 Mbps - Force Baseline
    }

    companion object {
        private const val TAG = "DisplayService"
        private const val channelId = "rtpDisplayStreamChannel"
        const val notifyId = 123456
        var INSTANCE: ScreenService? = null
        const val ACTION_START = "com.cesicorp.ludicrouslink.ACTION_START"
        const val ACTION_STOP = "com.cesicorp.ludicrouslink.ACTION_STOP"
        const val ACTION_QUIT = "com.cesicorp.ludicrouslink.ACTION_QUIT"
        const val ACTION_STREAM_STOPPED = "com.cesicorp.ludicrouslink.ACTION_STREAM_STOPPED"
    }

    private var notificationManager: NotificationManager? = null
    private var webSocketStreamer: WebSocketStreamer? = null
    private val mediaProjectionManager: MediaProjectionManager by lazy {
        applicationContext.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    var quality: Quality = Quality.MEDIUM

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        AppLog.i(TAG, "Streaming service created")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelId, NotificationManager.IMPORTANCE_HIGH)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createStreamer(): WebSocketStreamer {
        val q = quality
        AppLog.i(TAG, "Quality: ${q.name} (${q.width}x${q.height} @ ${q.bitrate / 1_000_000}Mbps)")
        return WebSocketStreamer(
            mediaProjectionManager,
            { event -> TouchInjectionService.inject(event) }, // Touch callback
            { reason -> onStreamDisconnected(reason) }, // Disconnect callback
            q.width,
            q.height,
            q.bitrate,
            q.fps,
            q.iFrameInterval,
            q.useHighProfile
        )
    }


    fun updateNotification() {
        showNotification(if (isStreaming()) "Streaming" else "Ready")
    }

    private fun showNotification(content: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("LudicrousLink")
            .setContentText(content)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)

        if (isStreaming()) {
            // While streaming: show Stop
            val stopIntent = Intent(this, ScreenService::class.java).apply { action = ACTION_STOP }
            val stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
        } else {
            // While idle: show Start (opens the app to initiate)
            val startIntent = Intent(this, MainActivity::class.java).apply {
                action = ACTION_START
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val startPi = PendingIntent.getActivity(this, 2, startIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(android.R.drawable.ic_media_play, "Start", startPi)
        }

        // Always show Quit
        val quitIntent = Intent(this, ScreenService::class.java).apply { action = ACTION_QUIT }
        val quitPi = PendingIntent.getService(this, 3, quitIntent, PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Quit", quitPi)

        val notification = builder.build()
        startForeground(notifyId, notification)
    }

    private fun keepAliveTrick() {
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.notification_icon)
            .setSilent(true)
            .setOngoing(false)
            .build()
        startForeground(notifyId, notification)
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppLog.i(TAG, "Stop requested from notification")
                stopStream()
                updateNotification()
                return START_NOT_STICKY
            }
            ACTION_QUIT -> {
                AppLog.i(TAG, "Quit requested from notification")
                stopStream()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }


    fun sendIntent(): Intent {
        return mediaProjectionManager.createScreenCaptureIntent()
    }

    fun isStreaming(): Boolean {
        return webSocketStreamer?.isStreaming() == true
    }

    fun stopStream() {
        if (webSocketStreamer?.isStreaming() == true) {
            webSocketStreamer?.stopStream()
        }
        webSocketStreamer = null
        AppLog.i(TAG, "Stream stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStream()
        INSTANCE = null
    }

    fun prepareStream(resultCode: Int, data: Intent): Boolean {
        showNotification("Streaming Controls")
        // Create a new streamer with current quality settings
        val streamer = createStreamer()
        webSocketStreamer = streamer
        return streamer.prepareMediaProjection(resultCode, data)
    }

    private fun onStreamDisconnected(reason: String) {
        val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
        uiHandler.post {
            AppLog.w(TAG, "Stream disconnected: $reason")
            stopStream()
            updateNotification()
            // Notify UI
            val intent = Intent(ACTION_STREAM_STOPPED)
            sendBroadcast(intent)
        }
    }

    fun startStream(host: String, port: Int) {
        val streamer = webSocketStreamer ?: return
        if (!streamer.isStreaming()) {
            val url = "ws://$host:$port/publish"
            AppLog.i(TAG, "Starting stream to $url")
            val success = streamer.startStream(url)
            if (success) {
                AppLog.i(TAG, "Stream started successfully")
            } else {
                AppLog.e(TAG, "Failed to start stream")
                // toast("Failed to start stream") // Toast needs context/handler
            }
        }
    }

    fun isPrepared(): Boolean {
        return webSocketStreamer?.isPrepared() == true
    }
}