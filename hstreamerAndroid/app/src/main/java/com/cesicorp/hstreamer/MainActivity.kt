package com.cesicorp.hstreamer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.IntentFilter


class MainActivity : AppCompatActivity() {

    private lateinit var btnStartStop: Button
    private lateinit var btnClearLog: Button
    private lateinit var tvStatus: TextView
    private lateinit var etGatewayAddress: EditText
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var rgQuality: RadioGroup

    private var isStreaming = false
    private val REQUEST_PERMISSIONS = 1001
    private val PREFS_NAME = "HStreamerPrefs"
    private val KEY_GATEWAY_ADDRESS = "gateway_address"
    private val KEY_QUALITY = "quality"
    private val DEFAULT_PORT = 8888

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val screenService = ScreenService.INSTANCE
            if (screenService != null) {
                if (!screenService.prepareStream(result.resultCode, result.data!!)) {
                    AppLog.e(TAG, "Failed to prepare stream")
                    toast("Failed to prepare stream")
                    return@registerForActivityResult
                }

                val address = etGatewayAddress.text.toString().trim()
                val (host, port) = parseAddress(address)

                if (host.isNotEmpty()) {
                    startStreamingService(result.data!!, host, port)
                } else {
                    AppLog.e(TAG, "Invalid address format: $address")
                    toast("Invalid address. Use host:port format.")
                }
            } else {
                AppLog.e(TAG, "Screen service not ready")
                toast("Screen service not ready")
            }
        } else {
            AppLog.w(TAG, "Screen capture permission denied")
            toast("Screen capture permission denied")
        }
    }

    private val streamReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenService.ACTION_STREAM_STOPPED) {
                isStreaming = false
                updateUI()
                toast("Stream disconnected")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        // Bind views
        btnStartStop = findViewById(R.id.btnStartStop)
        btnClearLog = findViewById(R.id.btnClearLog)
        tvStatus = findViewById(R.id.tvStatus)
        etGatewayAddress = findViewById(R.id.etGatewayAddress)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        rgQuality = findViewById(R.id.rgQuality)

        // Bind AppLog to UI
        AppLog.bind(tvLog, svLog)
        AppLog.i(TAG, "HStreamer started")

        // Restore saved settings
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(KEY_GATEWAY_ADDRESS, "")
        if (!saved.isNullOrEmpty()) {
            etGatewayAddress.setText(saved)
        }

        // Restore quality setting
        val savedQuality = prefs.getString(KEY_QUALITY, "MEDIUM")
        when (savedQuality) {
            "LOW" -> rgQuality.check(R.id.rbLow)
            "MEDIUM" -> rgQuality.check(R.id.rbMedium)
            "HIGH" -> rgQuality.check(R.id.rbHigh)
        }

        // Start screen service
        val intent = Intent(this, ScreenService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        btnStartStop.setOnClickListener {
            if (isStreaming) {
                stopStream()
            } else {
                val address = etGatewayAddress.text.toString().trim()
                if (address.isNotEmpty()) {
                    // Save settings
                    prefs.edit().putString(KEY_GATEWAY_ADDRESS, address).apply()
                    prefs.edit().putString(KEY_QUALITY, getSelectedQuality().name).apply()

                    // Set quality on service
                    ScreenService.INSTANCE?.quality = getSelectedQuality()

                    requestPermissionsAndStart()
                } else {
                    toast("Please enter a gateway address")
                }
            }
        }

        btnClearLog.setOnClickListener {
            AppLog.clear()
        }

        // Sync state if service is already running
        val screenService = ScreenService.INSTANCE
        if (screenService != null) {
            isStreaming = screenService.isStreaming()
        }

        updateUI()

        val filter = IntentFilter(ScreenService.ACTION_STREAM_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(streamReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(streamReceiver, filter)
        }
    }

    private fun parseAddress(address: String): Pair<String, Int> {
        val parts = address.split(":")
        return when {
            parts.size == 2 -> {
                val port = parts[1].toIntOrNull() ?: DEFAULT_PORT
                Pair(parts[0], port)
            }
            parts.size == 1 && parts[0].isNotEmpty() -> {
                // Just a host, use default port
                Pair(parts[0], DEFAULT_PORT)
            }
            else -> Pair("", DEFAULT_PORT)
        }
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                REQUEST_PERMISSIONS
            )
        } else {
            startMediaProjection()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startMediaProjection()
            } else {
                AppLog.w(TAG, "Permissions not granted")
                toast("Permissions required for streaming")
            }
        }
    }

    private fun startMediaProjection() {
        AppLog.i(TAG, "Requesting screen capture permission...")
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startStreamingService(data: Intent, host: String, port: Int) {
        AppLog.i(TAG, "Connecting to $host:$port...")
        val screenService = ScreenService.INSTANCE
        if (screenService != null) {
            if (screenService.isPrepared()) {
                screenService.startStream(host, port)
                AppLog.i(TAG, "Stream started")
            } else {
                AppLog.e(TAG, "Stream not prepared")
                toast("Stream not prepared")
            }
            isStreaming = screenService.isStreaming()
        } else {
            isStreaming = false
            AppLog.e(TAG, "Screen service is null")
            toast("Screen service is null")
        }

        updateUI()
    }

    private fun updateUI() {
        if (isStreaming) {
            btnStartStop.text = "Stop Streaming"
            tvStatus.text = "\u25CF Streaming"
            etGatewayAddress.isEnabled = false
            etGatewayAddress.alpha = 0.5f
            rgQuality.isEnabled = false
            for (i in 0 until rgQuality.childCount) {
                rgQuality.getChildAt(i).isEnabled = false
            }
        } else {
            btnStartStop.text = "Start Streaming"
            tvStatus.text = "\u23F8 Idle"
            etGatewayAddress.isEnabled = true
            etGatewayAddress.alpha = 1.0f
            rgQuality.isEnabled = true
            for (i in 0 until rgQuality.childCount) {
                rgQuality.getChildAt(i).isEnabled = true
            }
        }
        // Keep notification in sync
        ScreenService.INSTANCE?.updateNotification()
    }

    private fun getSelectedQuality(): ScreenService.Quality {
        return when (rgQuality.checkedRadioButtonId) {
            R.id.rbLow -> ScreenService.Quality.LOW
            R.id.rbMedium -> ScreenService.Quality.MEDIUM
            R.id.rbHigh -> ScreenService.Quality.HIGH
            else -> ScreenService.Quality.MEDIUM
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == ScreenService.ACTION_START && !isStreaming) {
            AppLog.i(TAG, "Start requested from notification")
            val address = etGatewayAddress.text.toString().trim()
            if (address.isNotEmpty()) {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(KEY_GATEWAY_ADDRESS, address).apply()
                prefs.edit().putString(KEY_QUALITY, getSelectedQuality().name).apply()
                ScreenService.INSTANCE?.quality = getSelectedQuality()
                requestPermissionsAndStart()
            } else {
                toast("Please enter a gateway address")
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(streamReceiver)
        AppLog.unbind()
        val screenService = ScreenService.INSTANCE
        if (screenService != null && !screenService.isStreaming()) {
            stopService(Intent(this, ScreenService::class.java))
        }
    }

    private fun stopStream() {
        val screenService = ScreenService.INSTANCE
        if (screenService?.isStreaming() == true) {
            AppLog.i(TAG, "Stopping stream...")
            screenService.stopStream()
            AppLog.i(TAG, "Stream stopped")
        }
        isStreaming = false
        updateUI()
    }
}
