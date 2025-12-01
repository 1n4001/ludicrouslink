package com.cesicorp.hstreamer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartStop: Button
    private lateinit var btnRefresh: Button
    private lateinit var tvStatus: TextView
    private lateinit var spinnerGateways: Spinner
    private lateinit var progressBar: ProgressBar
    private lateinit var tvDiscoveryStatus: TextView

    private var isStreaming = false
    private val REQUEST_PERMISSIONS = 1001

    private lateinit var serviceDiscovery: ServiceDiscovery
    private val discoveredGateways = mutableListOf<ServiceDiscovery.GatewayInfo>()
    private lateinit var gatewayAdapter: ArrayAdapter<String>
    private var selectedGateway: ServiceDiscovery.GatewayInfo? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            selectedGateway?.let { gateway ->
                startStreamingService(result.data!!, gateway)
            } ?: run {
                Toast.makeText(this, "Please select a gateway first", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStartStop = findViewById(R.id.btnStartStop)
        btnRefresh = findViewById(R.id.btnRefresh)
        tvStatus = findViewById(R.id.tvStatus)
        spinnerGateways = findViewById(R.id.spinnerGateways)
        progressBar = findViewById(R.id.progressBar)
        tvDiscoveryStatus = findViewById(R.id.tvDiscoveryStatus)

        // Setup gateway spinner
        gatewayAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        gatewayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerGateways.adapter = gatewayAdapter

        spinnerGateways.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < discoveredGateways.size) {
                    selectedGateway = discoveredGateways[position]
                    Log.i(TAG, "Selected gateway: ${selectedGateway?.host}:${selectedGateway?.port}")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedGateway = null
            }
        }

        // Initialize service discovery
        serviceDiscovery = ServiceDiscovery(this)

        btnStartStop.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                if (selectedGateway != null) {
                    requestPermissionsAndStart()
                } else {
                    Toast.makeText(this, "Please select a gateway", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnRefresh.setOnClickListener {
            startGatewayDiscovery()
        }

        updateUI()
        startGatewayDiscovery()
    }

    private fun startGatewayDiscovery() {
        Log.i(TAG, "Starting gateway discovery")

        progressBar.visibility = View.VISIBLE
        tvDiscoveryStatus.text = "Searching for gateways..."
        discoveredGateways.clear()
        gatewayAdapter.clear()
        gatewayAdapter.notifyDataSetChanged()

        serviceDiscovery.startDiscovery { gateway ->
            runOnUiThread {
                Log.i(TAG, "Gateway found: ${gateway.name} at ${gateway.host}:${gateway.port}")

                if (!discoveredGateways.any { it.host == gateway.host && it.port == gateway.port }) {
                    discoveredGateways.add(gateway)
                    gatewayAdapter.add("${gateway.name} (${gateway.host}:${gateway.port})")
                    gatewayAdapter.notifyDataSetChanged()

                    tvDiscoveryStatus.text = "Found ${discoveredGateways.size} gateway(s)"

                    // Auto-select first gateway
                    if (discoveredGateways.size == 1) {
                        spinnerGateways.setSelection(0)
                    }
                }
            }
        }

        // Stop discovery after 10 seconds
        btnRefresh.postDelayed({
            progressBar.visibility = View.GONE
            if (discoveredGateways.isEmpty()) {
                tvDiscoveryStatus.text = "No gateways found. Make sure gateway is running."
            }
        }, 10000)
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
                Toast.makeText(this, "Permissions required for streaming", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startMediaProjection() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startStreamingService(data: Intent, gateway: ServiceDiscovery.GatewayInfo) {
        // Build RTMP URL: rtmp://gateway-ip:port/live/stream
        val rtmpUrl = "rtmp://${gateway.host}:${gateway.port}/live/stream"

        val serviceIntent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_RESULT_DATA, data)
            putExtra(StreamingService.EXTRA_GATEWAY_URL, rtmpUrl)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        isStreaming = true
        updateUI()
    }

    private fun stopStreaming() {
        val serviceIntent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        startService(serviceIntent)

        isStreaming = false
        updateUI()
    }

    private fun updateUI() {
        if (isStreaming) {
            btnStartStop.text = "Stop Streaming"
            tvStatus.text = "Status: Streaming to ${selectedGateway?.name ?: "Gateway"}"
            btnRefresh.isEnabled = false
            spinnerGateways.isEnabled = false
        } else {
            btnStartStop.text = "Start Streaming"
            tvStatus.text = "Status: Idle"
            btnRefresh.isEnabled = true
            spinnerGateways.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceDiscovery.stopDiscovery()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
