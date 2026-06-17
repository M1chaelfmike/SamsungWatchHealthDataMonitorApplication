package com.samsung.health.sensorsdksample.edatracking.pairing

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.samsung.health.sensorsdksample.edatracking.config.RemoteWatchConfiguration
import com.samsung.health.sensorsdksample.edatracking.config.WatchConfigStore
import com.samsung.health.sensorsdksample.edatracking.config.WatchConfiguration
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the watch pairing lifecycle and exposes pairing state to the UI/service.
 */
@Singleton
class WatchPairingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: WatchConfigStore,
    private val configReceiver: WatchConfigReceiver,
    private val networkInfoProvider: WatchNetworkInfoProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val pairingCode = Random.nextInt(100000, 999999).toString()
    private var started = false
    private var lastNetworkKey: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val _pairingState = MutableStateFlow(
        buildState(
            configuration = configStore.configuration.value,
            networkInfo = networkInfoProvider.getCurrentInfo(),
            requiresPairing = !configStore.configuration.value.paired,
            reason = if (configStore.configuration.value.paired) {
                WatchPairingReason.PAIRED
            } else {
                WatchPairingReason.FIRST_LAUNCH
            },
            message = if (configStore.configuration.value.paired) {
                "Configuration ready"
            } else {
                "Waiting for server configuration"
            }
        )
    )
    val pairingState = _pairingState.asStateFlow()

    /**
     * Starts the local receiver and network monitoring used during pairing.
     */
    fun start() {
        if (started) {
            startReceiverIfNeeded()
            refreshNetworkInfo()
            return
        }

        started = true
        refreshNetworkInfo()
        startReceiverIfNeeded()
        registerNetworkCallback()

        scope.launch {
            configStore.configuration.collect { configuration ->
                val current = _pairingState.value
                _pairingState.value = current.copy(
                    configuration = configuration,
                    canSkip = configuration.canUseExistingTarget,
                    requiresPairing = if (configuration.paired && current.reason == WatchPairingReason.PAIRED) {
                        false
                    } else {
                        current.requiresPairing
                    }
                )
            }
        }
    }

    /**
     * Marks the current stored configuration as accepted without receiving a new backend push.
     */
    fun skipPairingWithCurrentConfiguration() {
        val current = _pairingState.value
        if (!current.canSkip) {
            return
        }

        val configuration = configStore.markPairedWithExistingConfiguration()
        _pairingState.value = buildState(
            configuration = configuration,
            networkInfo = networkInfoProvider.getCurrentInfo(),
            requiresPairing = false,
            reason = WatchPairingReason.PAIRED,
            message = "Using saved configuration"
        )
    }

    /**
     * Refreshes IP/MAC information shown in the pairing screen.
     */
    fun refreshNetworkInfo(showMessage: Boolean = false) {
        val networkInfo = networkInfoProvider.getCurrentInfo()
        lastNetworkKey = networkInfo.toNetworkKey()
        _pairingState.value = _pairingState.value.copy(
            watchIp = networkInfo.ipAddress,
            macAddress = networkInfo.macAddress,
            receiverRunning = configReceiver.isRunning,
            message = if (showMessage) {
                "Network info refreshed"
            } else {
                _pairingState.value.message
            }
        )
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) {
            return
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleNetworkChanged()
            }

            override fun onLost(network: Network) {
                handleNetworkChanged()
            }
        }
        networkCallback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    private fun handleNetworkChanged() {
        val networkInfo = networkInfoProvider.getCurrentInfo()
        val networkKey = networkInfo.toNetworkKey()
        val previousNetworkKey = lastNetworkKey
        lastNetworkKey = networkKey
        startReceiverIfNeeded()

        val configuration = configStore.configuration.value
        val shouldReviewNetwork = previousNetworkKey != null &&
            previousNetworkKey != networkKey &&
            configuration.canUseExistingTarget

        _pairingState.value = buildState(
            configuration = configuration,
            networkInfo = networkInfo,
            requiresPairing = shouldReviewNetwork || !configuration.paired,
            reason = if (shouldReviewNetwork) {
                WatchPairingReason.NETWORK_CHANGED
            } else if (configuration.paired) {
                WatchPairingReason.PAIRED
            } else {
                WatchPairingReason.WAITING_FOR_CONFIG
            },
            message = if (shouldReviewNetwork) {
                "Network changed. Confirm or skip pairing."
            } else if (configuration.paired) {
                "Configuration ready"
            } else {
                "Waiting for server configuration"
            }
        )
    }

    private fun startReceiverIfNeeded() {
        configReceiver.start(
            port = PAIRING_PORT,
            pairingCodeProvider = { pairingCode },
            currentConfigurationProvider = { configStore.configuration.value },
            onConfigurationReceived = ::onConfigurationReceived,
            onError = ::onReceiverError
        )
        _pairingState.value = _pairingState.value.copy(receiverRunning = configReceiver.isRunning)
    }

    private fun onConfigurationReceived(update: RemoteWatchConfiguration): WatchConfiguration {
        val configuration = configStore.saveRemoteConfiguration(update)
        _pairingState.value = buildState(
            configuration = configuration,
            networkInfo = networkInfoProvider.getCurrentInfo(),
            requiresPairing = false,
            reason = WatchPairingReason.PAIRED,
            message = "Configuration received"
        )
        return configuration
    }

    private fun onReceiverError(message: String) {
        val current = _pairingState.value
        _pairingState.value = current.copy(
            receiverRunning = false,
            requiresPairing = true,
            reason = WatchPairingReason.RECEIVER_ERROR,
            message = message
        )
    }

    private fun buildState(
        configuration: WatchConfiguration,
        networkInfo: WatchNetworkInfo,
        requiresPairing: Boolean,
        reason: WatchPairingReason,
        message: String?
    ): WatchPairingState {
        return WatchPairingState(
            requiresPairing = requiresPairing,
            canSkip = configuration.canUseExistingTarget,
            receiverRunning = configReceiver.isRunning,
            receiverPort = PAIRING_PORT,
            watchIp = networkInfo.ipAddress,
            macAddress = networkInfo.macAddress,
            pairingCode = pairingCode,
            configuration = configuration,
            reason = reason,
            message = message
        )
    }

    private fun WatchNetworkInfo.toNetworkKey(): String {
        return listOf(
            ipAddress?.lowercase(Locale.US).orEmpty(),
            macAddress?.lowercase(Locale.US).orEmpty()
        ).joinToString("|")
    }

    companion object {
        const val PAIRING_PORT = 8765
    }
}
