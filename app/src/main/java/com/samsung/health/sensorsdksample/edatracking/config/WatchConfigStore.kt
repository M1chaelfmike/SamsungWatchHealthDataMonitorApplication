package com.samsung.health.sensorsdksample.edatracking.config

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores watch identity and backend upload target in SharedPreferences.
 */
@Singleton
class WatchConfigStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _configuration = MutableStateFlow(loadConfiguration())
    val configuration = _configuration.asStateFlow()

    /**
     * Saves configuration pushed by the dashboard pairing flow and marks it as paired.
     */
    fun saveRemoteConfiguration(update: RemoteWatchConfiguration): WatchConfiguration {
        val current = _configuration.value
        val normalizedWatchId = update.watchId?.trim().takeUnless { it.isNullOrBlank() } ?: current.watchId
        val normalizedHost = normalizeHost(update.serverHost)
        val normalizedPort = normalizePort(update.serverPort)

        preferences.edit()
            .putString(KEY_WATCH_ID, normalizedWatchId)
            .putString(KEY_UPLOAD_HOST, normalizedHost)
            .putInt(KEY_UPLOAD_PORT, normalizedPort)
            .putBoolean(KEY_PAIRED, true)
            .apply()

        return publish(loadConfiguration())
    }

    /**
     * Updates only the upload host and port while keeping the current pairing state.
     */
    fun saveUploadTarget(host: String, port: Int): WatchConfiguration {
        val normalizedHost = normalizeHost(host)
        val normalizedPort = normalizePort(port)

        preferences.edit()
            .putString(KEY_UPLOAD_HOST, normalizedHost)
            .putInt(KEY_UPLOAD_PORT, normalizedPort)
            .apply()

        return publish(loadConfiguration())
    }

    /**
     * Allows the app to continue with a previously saved target after review.
     */
    fun markPairedWithExistingConfiguration(): WatchConfiguration {
        preferences.edit()
            .putBoolean(KEY_PAIRED, true)
            .apply()

        return publish(loadConfiguration())
    }

    private fun publish(configuration: WatchConfiguration): WatchConfiguration {
        _configuration.value = configuration
        return configuration
    }

    private fun loadConfiguration(): WatchConfiguration {
        migrateLegacyUploadTargetIfNeeded()

        val hasStoredTarget = preferences.contains(KEY_UPLOAD_HOST) || preferences.contains(KEY_UPLOAD_PORT)
        val watchId = preferences.getString(KEY_WATCH_ID, DEFAULT_WATCH_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_WATCH_ID
        val serverHost = preferences.getString(KEY_UPLOAD_HOST, DEFAULT_UPLOAD_HOST)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_UPLOAD_HOST
        val serverPort = preferences.getInt(KEY_UPLOAD_PORT, DEFAULT_UPLOAD_PORT).coerceIn(1, 65535)
        val paired = preferences.getBoolean(KEY_PAIRED, false)

        return WatchConfiguration(
            watchId = watchId,
            serverHost = serverHost,
            serverPort = serverPort,
            paired = paired,
            hasStoredTarget = hasStoredTarget
        )
    }

    private fun migrateLegacyUploadTargetIfNeeded() {
        val storedHost = preferences.getString(KEY_UPLOAD_HOST, null)?.trim()
        val storedPort = if (preferences.contains(KEY_UPLOAD_PORT)) {
            preferences.getInt(KEY_UPLOAD_PORT, DEFAULT_UPLOAD_PORT)
        } else {
            null
        }

        val editor = preferences.edit()
        var changed = false
        if (storedHost == LEGACY_UPLOAD_HOST) {
            editor.putString(KEY_UPLOAD_HOST, DEFAULT_UPLOAD_HOST)
            changed = true
        }
        if (storedPort == LEGACY_UPLOAD_PORT || storedPort == LEGACY_DEV_PORT) {
            editor.putInt(KEY_UPLOAD_PORT, DEFAULT_UPLOAD_PORT)
            changed = true
        }
        if (changed) {
            editor.apply()
        }
    }

    private fun normalizeHost(host: String): String {
        return host.trim().ifEmpty { DEFAULT_UPLOAD_HOST }
    }

    private fun normalizePort(port: Int): Int {
        return port.coerceIn(1, 65535)
    }

    companion object {
        private const val PREFERENCES_NAME = "continuous_tracking"
        private const val KEY_UPLOAD_HOST = "upload_host"
        private const val KEY_UPLOAD_PORT = "upload_port"
        private const val KEY_WATCH_ID = "watch_id"
        private const val KEY_PAIRED = "watch_paired_v1"

        private const val DEFAULT_WATCH_ID = "real-watch-001"
        private const val DEFAULT_UPLOAD_HOST = "192.168.0.5"
        private const val DEFAULT_UPLOAD_PORT = 3100
        private const val LEGACY_UPLOAD_HOST = "192.168.0.8"
        private const val LEGACY_UPLOAD_PORT = 8080
        private const val LEGACY_DEV_PORT = 5000
    }
}
