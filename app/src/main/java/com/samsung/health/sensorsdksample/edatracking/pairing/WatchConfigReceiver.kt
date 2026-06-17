package com.samsung.health.sensorsdksample.edatracking.pairing

import android.util.Log
import com.samsung.health.sensorsdksample.edatracking.config.RemoteWatchConfiguration
import com.samsung.health.sensorsdksample.edatracking.config.WatchEndpoints
import com.samsung.health.sensorsdksample.edatracking.config.WatchConfiguration
import com.samsung.health.sensorsdksample.edatracking.presentation.MainActivity.Companion.APP_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight HTTP receiver that lets the dashboard push server configuration to the watch.
 */
@Singleton
class WatchConfigReceiver @Inject constructor(
    private val handshakeClient: WatchPairingHandshakeClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var receiverJob: Job? = null

    val isRunning: Boolean
        get() = receiverJob?.isActive == true

    /**
     * Starts listening for pairing/configuration requests on the supplied local port.
     */
    fun start(
        port: Int,
        pairingCodeProvider: () -> String,
        currentConfigurationProvider: () -> WatchConfiguration,
        onConfigurationReceived: (RemoteWatchConfiguration) -> WatchConfiguration,
        onError: (String) -> Unit
    ) {
        if (isRunning) {
            return
        }

        receiverJob = scope.launch {
            try {
                ServerSocket(port).use { socket ->
                    serverSocket = socket
                    while (isActive) {
                        val client = try {
                            socket.accept()
                        } catch (exception: SocketException) {
                            if (isActive) {
                                onError("Pairing receiver stopped: ${exception.message}")
                            }
                            break
                        }

                        launch {
                            handleClient(
                                client = client,
                                pairingCodeProvider = pairingCodeProvider,
                                currentConfigurationProvider = currentConfigurationProvider,
                                onConfigurationReceived = onConfigurationReceived
                            )
                        }
                    }
                }
            } catch (exception: Exception) {
                Log.w(APP_TAG, "Watch pairing receiver failed", exception)
                onError(exception.message ?: exception.javaClass.simpleName)
            } finally {
                serverSocket = null
            }
        }
    }

    /**
     * Stops the receiver and closes the underlying server socket.
     */
    fun stop() {
        receiverJob?.cancel()
        receiverJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handleClient(
        client: Socket,
        pairingCodeProvider: () -> String,
        currentConfigurationProvider: () -> WatchConfiguration,
        onConfigurationReceived: (RemoteWatchConfiguration) -> WatchConfiguration
    ) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine().orEmpty()
            val request = parseRequestLine(requestLine)
            if (request == null) {
                writeJson(socket, 400, JSONObject().put("error", "Invalid request"))
                return
            }

            val headers = readHeaders(reader)
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val body = if (contentLength > 0) {
                readBody(reader, contentLength)
            } else {
                ""
            }

            when {
                request.method == "GET" && request.path == "/health" -> {
                    writeJson(socket, 200, buildStatusPayload(currentConfigurationProvider()))
                }

                request.method == "GET" && request.path == "/config" -> {
                    writeJson(socket, 200, buildConfigPayload(currentConfigurationProvider()))
                }

                request.method == "POST" && request.path == "/config" -> {
                    handleConfigPost(
                        socket = socket,
                        body = body,
                        expectedPairingCode = pairingCodeProvider(),
                        currentConfiguration = currentConfigurationProvider(),
                        onConfigurationReceived = onConfigurationReceived
                    )
                }

                else -> {
                    writeJson(socket, 404, JSONObject().put("error", "Unknown path"))
                }
            }
        }
    }

    private fun handleConfigPost(
        socket: Socket,
        body: String,
        expectedPairingCode: String,
        currentConfiguration: WatchConfiguration,
        onConfigurationReceived: (RemoteWatchConfiguration) -> WatchConfiguration
    ) {
        val payload = try {
            JSONObject(body)
        } catch (exception: Exception) {
            writeJson(socket, 400, JSONObject().put("error", "Invalid JSON"))
            return
        }

        val providedPairingCode = payload.optString("pairingCode", "")
        val pairingCodeRequired = !currentConfiguration.paired
        if (pairingCodeRequired && expectedPairingCode.isNotBlank() && providedPairingCode != expectedPairingCode) {
            writeJson(socket, 403, JSONObject().put("error", "Pairing code does not match"))
            return
        }

        val serverHost = payload.optNullableString("serverHost")
            ?: payload.optNullableString("uploadHost")
        val serverPort = payload.optNullableInt("serverPort")
            ?: payload.optNullableInt("uploadPort")
        if (serverHost.isNullOrBlank() || serverPort == null || serverPort !in 1..65535) {
            writeJson(socket, 400, JSONObject().put("error", "serverHost and serverPort are required"))
            return
        }

        val remoteConfiguration = RemoteWatchConfiguration(
            watchId = payload.optNullableString("watchId") ?: currentConfiguration.watchId,
            serverHost = serverHost,
            serverPort = serverPort
        )
        val handshakeResult = handshakeClient.confirmServerReachable(
            configuration = remoteConfiguration,
            pairingChallenge = payload.optNullableString("pairingChallenge")
        )
        if (!handshakeResult.success) {
            writeJson(
                socket,
                502,
                JSONObject()
                    .put("error", handshakeResult.message)
                    .put("handshake", false)
            )
            return
        }

        val savedConfiguration = onConfigurationReceived(remoteConfiguration)

        writeJson(
            socket,
            200,
            buildConfigPayload(savedConfiguration)
                .put("success", true)
                .put("message", "Configuration saved")
        )
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                break
            }
            val separatorIndex = line.indexOf(':')
            if (separatorIndex > 0) {
                val name = line.substring(0, separatorIndex).trim().lowercase(Locale.US)
                val value = line.substring(separatorIndex + 1).trim()
                headers[name] = value
            }
        }
        return headers
    }

    private fun readBody(reader: BufferedReader, contentLength: Int): String {
        val buffer = CharArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val count = reader.read(buffer, offset, contentLength - offset)
            if (count <= 0) {
                break
            }
            offset += count
        }
        return String(buffer, 0, offset)
    }

    private fun writeJson(socket: Socket, statusCode: Int, payload: JSONObject) {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            502 -> "Bad Gateway"
            else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)

        socket.getOutputStream().use { output ->
            output.write(headers)
            output.write(body)
            output.flush()
        }
    }

    private fun buildStatusPayload(configuration: WatchConfiguration): JSONObject {
        return buildConfigPayload(configuration)
            .put("status", "ok")
            .put("receiverPort", WatchPairingManager.PAIRING_PORT)
    }

    private fun buildConfigPayload(configuration: WatchConfiguration): JSONObject {
        return JSONObject().apply {
            put("watchId", configuration.watchId)
            put("serverHost", configuration.serverHost)
            put("serverPort", configuration.serverPort)
            put("paired", configuration.paired)
            put(
                "uploadEndpoint",
                WatchEndpoints.samsungWatchUpload(
                    host = configuration.serverHost,
                    port = configuration.serverPort
                )
            )
        }
    }

    private fun parseRequestLine(requestLine: String): HttpRequestLine? {
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            return null
        }

        val method = parts[0].uppercase(Locale.US)
        val path = parts[1].substringBefore('?')
        return HttpRequestLine(method = method, path = path)
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) {
            return null
        }
        return optString(name).trim().takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        if (!has(name) || isNull(name)) {
            return null
        }
        return optInt(name)
    }

    private data class HttpRequestLine(
        val method: String,
        val path: String
    )
}
