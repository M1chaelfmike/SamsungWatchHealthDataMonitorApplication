package com.samsung.health.sensorsdksample.edatracking.tracking

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.health.sensorsdksample.edatracking.presentation.MainActivity.Companion.APP_TAG
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SharedHealthTrackingServiceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var healthTrackingService: HealthTrackingService? = null
    private var activeClients = 0
    private var isConnecting = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _connectionErrors = MutableSharedFlow<HealthTrackerException>()
    val connectionErrors = _connectionErrors.asSharedFlow()

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            isConnecting = false
            _isConnected.value = true
        }

        override fun onConnectionEnded() {
            isConnecting = false
            _isConnected.value = false
            healthTrackingService = null
        }

        override fun onConnectionFailed(exception: HealthTrackerException) {
            isConnecting = false
            _isConnected.value = false
            healthTrackingService = null
            _connectionErrors.tryEmit(exception)
        }
    }

    fun acquireConnection() {
        activeClients += 1
        if (healthTrackingService == null && !isConnecting) {
            connectInternal()
        }
    }

    fun releaseConnection() {
        if (activeClients > 0) {
            activeClients -= 1
        }

        if (activeClients == 0) {
            isConnecting = false
            healthTrackingService?.disconnectService()
            healthTrackingService = null
            _isConnected.value = false
        }
    }

    fun getHealthTracker(type: HealthTrackerType): HealthTracker? {
        return try {
            healthTrackingService?.getHealthTracker(type)
        } catch (exception: IllegalStateException) {
            Log.w(APP_TAG, "Health tracker unavailable because service binder is not ready", exception)
            handleInvalidServiceState()
            null
        } catch (exception: IllegalArgumentException) {
            Log.w(APP_TAG, "Health tracker request rejected for type=$type", exception)
            null
        } catch (exception: UnsupportedOperationException) {
            Log.w(APP_TAG, "Health tracker type is not supported for type=$type", exception)
            null
        }
    }

    fun getPpgHealthTracker(type: HealthTrackerType, ppgTypes: Set<PpgType>): HealthTracker? {
        return try {
            healthTrackingService?.getHealthTracker(type, ppgTypes)
        } catch (exception: IllegalStateException) {
            Log.w(APP_TAG, "PPG tracker unavailable because service binder is not ready", exception)
            handleInvalidServiceState()
            null
        } catch (exception: IllegalArgumentException) {
            Log.w(APP_TAG, "PPG tracker request rejected for type=$type and ppgTypes=$ppgTypes", exception)
            null
        } catch (exception: UnsupportedOperationException) {
            Log.w(APP_TAG, "PPG tracker is not supported for type=$type and ppgTypes=$ppgTypes", exception)
            null
        }
    }

    fun getSupportedTrackers(): List<HealthTrackerType>? {
        return try {
            healthTrackingService?.trackingCapability?.supportHealthTrackerTypes
        } catch (exception: IllegalStateException) {
            Log.w(APP_TAG, "Tracker capability unavailable because service binder is not ready", exception)
            handleInvalidServiceState()
            null
        }
    }

    private fun connectInternal() {
        healthTrackingService = HealthTrackingService(connectionListener, context)
        isConnecting = true
        healthTrackingService?.connectService()
    }

    private fun handleInvalidServiceState() {
        isConnecting = false
        healthTrackingService = null
        _isConnected.value = false
    }
}