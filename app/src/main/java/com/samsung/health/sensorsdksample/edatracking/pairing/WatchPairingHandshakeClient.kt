package com.samsung.health.sensorsdksample.edatracking.pairing

import com.samsung.health.sensorsdksample.edatracking.config.RemoteWatchConfiguration
import com.samsung.health.sensorsdksample.edatracking.config.WatchEndpoints
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result returned after the watch asks the backend to confirm a pairing request.
 */
data class WatchPairingHandshakeResult(
    val success: Boolean,
    val message: String
)

/**
 * Performs the server-side handshake used before accepting a pushed watch configuration.
 */
@Singleton
class WatchPairingHandshakeClient @Inject constructor(
    private val networkInfoProvider: WatchNetworkInfoProvider
) {
    /**
     * Confirms that the configured backend can reach and validate this watch pairing session.
     */
    fun confirmServerReachable(
        configuration: RemoteWatchConfiguration,
        pairingChallenge: String?
    ): WatchPairingHandshakeResult {
        if (pairingChallenge.isNullOrBlank()) {
            return WatchPairingHandshakeResult(false, "Missing pairing challenge")
        }

        val endpoint = WatchEndpoints.pairingHandshake(
            host = configuration.serverHost,
            port = configuration.serverPort
        )
        val networkInfo = networkInfoProvider.getCurrentInfo()
        val payload = JSONObject().apply {
            put("pairingChallenge", pairingChallenge)
            put("watchId", configuration.watchId)
            put("serverHost", configuration.serverHost)
            put("serverPort", configuration.serverPort)
            networkInfo.ipAddress?.let { put("watchIp", it) }
            networkInfo.macAddress?.let { put("macAddress", it) }
        }

        return try {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6_000
                readTimeout = 6_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            connection.disconnect()

            if (responseCode in 200..299) {
                WatchPairingHandshakeResult(true, "Server handshake confirmed")
            } else {
                WatchPairingHandshakeResult(false, parseErrorMessage(responseText, "Server handshake failed with HTTP $responseCode"))
            }
        } catch (exception: Exception) {
            WatchPairingHandshakeResult(
                false,
                "Cannot reach server at ${configuration.serverHost}:${configuration.serverPort}: ${exception.message ?: exception.javaClass.simpleName}"
            )
        }
    }

    private fun parseErrorMessage(responseText: String, fallback: String): String {
        if (responseText.isBlank()) {
            return fallback
        }
        return try {
            JSONObject(responseText).optString("error", fallback)
        } catch (_: Exception) {
            responseText.take(160)
        }
    }
}
