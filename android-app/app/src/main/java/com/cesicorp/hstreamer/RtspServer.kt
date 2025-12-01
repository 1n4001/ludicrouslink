package com.cesicorp.hstreamer

import android.util.Base64
import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RtspServer(
    private val port: Int,
    private val screenEncoder: ScreenEncoder,
    private val audioEncoder: AudioEncoder
) {
    private var serverSocket: ServerSocket? = null
    private val clients = ConcurrentHashMap<Int, ClientSession>()
    private val clientIdCounter = AtomicInteger(0)
    private var isRunning = false

    private inner class ClientSession(private val socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
        private var cseq = 0
        private var sessionId = ""
        private var clientRtpPort = 0
        private var clientRtcpPort = 0
        private var isStreaming = false

        fun handle() {
            Thread {
                try {
                    while (isRunning && !socket.isClosed) {
                        val request = readRequest() ?: break
                        handleRequest(request)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Client session error", e)
                } finally {
                    close()
                }
            }.start()
        }

        private fun readRequest(): String? {
            val request = StringBuilder()
            var line: String?

            line = reader.readLine() ?: return null
            request.append(line).append("\r\n")

            while (reader.readLine()?.also { line = it }?.isNotEmpty() == true) {
                request.append(line).append("\r\n")
            }

            return request.toString()
        }

        private fun handleRequest(request: String) {
            val lines = request.split("\r\n")
            val requestLine = lines[0]

            cseq = lines.find { it.startsWith("CSeq:") }
                ?.substringAfter("CSeq:")?.trim()?.toIntOrNull() ?: 0

            when {
                requestLine.startsWith("OPTIONS") -> handleOptions()
                requestLine.startsWith("DESCRIBE") -> handleDescribe()
                requestLine.startsWith("SETUP") -> handleSetup(request)
                requestLine.startsWith("PLAY") -> handlePlay()
                requestLine.startsWith("TEARDOWN") -> handleTeardown()
                else -> sendResponse(400, "Bad Request")
            }
        }

        private fun handleOptions() {
            val response = """
                RTSP/1.0 200 OK
                CSeq: $cseq
                Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN

            """.trimIndent()
            writer.write(response)
            writer.flush()
        }

        private fun handleDescribe() {
            val sps = screenEncoder.getSPS()
            val pps = screenEncoder.getPPS()

            if (sps == null || pps == null) {
                sendResponse(500, "Encoder not ready")
                return
            }

            val spsBase64 = Base64.encodeToString(sps, Base64.NO_WRAP)
            val ppsBase64 = Base64.encodeToString(pps, Base64.NO_WRAP)

            val sdp = """
                v=0
                o=- 0 0 IN IP4 127.0.0.1
                s=HStreamer
                t=0 0
                m=video 0 RTP/AVP 96
                a=rtpmap:96 H264/90000
                a=fmtp:96 packetization-mode=1;profile-level-id=42001f;sprop-parameter-sets=$spsBase64,$ppsBase64
                a=control:track0
                m=audio 0 RTP/AVP 97
                a=rtpmap:97 mpeg4-generic/${audioEncoder.getSampleRate()}/2
                a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1210
                a=control:track1
            """.trimIndent()

            val response = """
                RTSP/1.0 200 OK
                CSeq: $cseq
                Content-Type: application/sdp
                Content-Length: ${sdp.length}

                $sdp
            """.trimIndent()

            writer.write(response)
            writer.flush()
        }

        private fun handleSetup(request: String) {
            sessionId = System.currentTimeMillis().toString()

            // Parse client ports from Transport header
            val transportLine = request.split("\r\n").find { it.startsWith("Transport:") }
            transportLine?.let {
                val clientPortMatch = "client_port=(\\d+)-(\\d+)".toRegex().find(it)
                if (clientPortMatch != null) {
                    clientRtpPort = clientPortMatch.groupValues[1].toInt()
                    clientRtcpPort = clientPortMatch.groupValues[2].toInt()
                }
            }

            val response = """
                RTSP/1.0 200 OK
                CSeq: $cseq
                Session: $sessionId
                Transport: RTP/AVP;unicast;client_port=$clientRtpPort-$clientRtcpPort;server_port=5000-5001

            """.trimIndent()

            writer.write(response)
            writer.flush()
        }

        private fun handlePlay() {
            isStreaming = true
            startStreaming()

            val response = """
                RTSP/1.0 200 OK
                CSeq: $cseq
                Session: $sessionId
                RTP-Info: url=rtsp://localhost:$port/live/track0;seq=0;rtptime=0

            """.trimIndent()

            writer.write(response)
            writer.flush()
        }

        private fun handleTeardown() {
            isStreaming = false
            sendResponse(200, "OK")
            close()
        }

        private fun startStreaming() {
            Thread {
                Log.i(TAG, "Started streaming to client")
                var videoSeq = 0
                var audioSeq = 0

                while (isStreaming && isRunning) {
                    try {
                        // Stream video frames
                        screenEncoder.getNextFrame()?.let { videoFrame ->
                            // In a real implementation, send RTP packets here
                            // For simplicity, we're demonstrating the structure
                            videoSeq++
                        }

                        // Stream audio frames
                        audioEncoder.getNextFrame()?.let { audioFrame ->
                            // In a real implementation, send RTP packets here
                            audioSeq++
                        }

                        Thread.sleep(10)
                    } catch (e: Exception) {
                        if (isStreaming) {
                            Log.e(TAG, "Streaming error", e)
                        }
                        break
                    }
                }
                Log.i(TAG, "Stopped streaming to client")
            }.start()
        }

        private fun sendResponse(code: Int, message: String) {
            val response = """
                RTSP/1.0 $code $message
                CSeq: $cseq

            """.trimIndent()
            writer.write(response)
            writer.flush()
        }

        fun close() {
            isStreaming = false
            try {
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client", e)
            }
        }
    }

    fun start() {
        isRunning = true

        Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "RTSP server started on port $port")

                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        clientSocket?.let {
                            val clientId = clientIdCounter.incrementAndGet()
                            val session = ClientSession(it)
                            clients[clientId] = session
                            session.handle()

                            Log.i(TAG, "Client connected: $clientId")
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting client", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            }
        }.start()
    }

    fun stop() {
        isRunning = false

        clients.values.forEach { it.close() }
        clients.clear()

        try {
            serverSocket?.close()
            Log.i(TAG, "RTSP server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    companion object {
        private const val TAG = "RtspServer"
    }
}
