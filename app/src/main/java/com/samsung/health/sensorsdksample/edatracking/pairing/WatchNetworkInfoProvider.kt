package com.samsung.health.sensorsdksample.edatracking.pairing

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

data class WatchNetworkInfo(
    val ipAddress: String?,
    val macAddress: String?
)

@Singleton
class WatchNetworkInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun getCurrentInfo(): WatchNetworkInfo {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces().toList()
        } catch (_: Exception) {
            emptyList()
        }
        val activeInterface = interfaces.firstOrNull { networkInterface ->
            try {
                !networkInterface.isLoopback &&
                    networkInterface.isUp &&
                    networkInterface.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress }
            } catch (_: Exception) {
                false
            }
        }

        val ipAddress = activeInterface
            ?.inetAddresses
            ?.toList()
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress

        val macAddress = try {
            activeInterface?.hardwareAddress?.formatMacAddress()
        } catch (_: Exception) {
            null
        }
            ?: fallbackDeviceIdentifier()

        return WatchNetworkInfo(
            ipAddress = ipAddress,
            macAddress = macAddress
        )
    }

    private fun fallbackDeviceIdentifier(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"
        return "ANDROID_ID:${androidId.take(12)}"
    }

    private fun ByteArray.formatMacAddress(): String {
        return joinToString(":") { byte -> "%02X".format(byte) }
    }
}
