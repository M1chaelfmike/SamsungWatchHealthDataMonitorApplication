package com.samsung.health.sensorsdksample.edatracking.config

/**
 * Builds backend endpoints used by the watch.
 *
 * Keeping endpoint construction in one place avoids slightly different URL
 * formats between pairing, status responses, and sensor uploads.
 */
object WatchEndpoints {
    private const val HTTP_SCHEME = "http"
    private const val SAMSUNG_WATCH_UPLOAD_PATH = "/api/samsung-watch"
    private const val PAIRING_HANDSHAKE_PATH = "/api/watch-pairing/handshake"

    fun samsungWatchUpload(host: String, port: Int): String {
        return httpEndpoint(host = host, port = port, path = SAMSUNG_WATCH_UPLOAD_PATH)
    }

    fun pairingHandshake(host: String, port: Int): String {
        return httpEndpoint(host = host, port = port, path = PAIRING_HANDSHAKE_PATH)
    }

    private fun httpEndpoint(host: String, port: Int, path: String): String {
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        return "$HTTP_SCHEME://${host.trim()}:$port$normalizedPath"
    }
}
