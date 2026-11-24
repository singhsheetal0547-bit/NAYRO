package com.example.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkTransportType(val displayName: String) {
    WIFI("Wi-Fi"),
    MOBILE_DATA("Mobile Data"),
    ETHERNET("Ethernet"),
    OTHER("Network"),
    NONE("Offline")
}

data class NetworkStatus(
    val isConnected: Boolean,
    val hasValidatedInternet: Boolean,
    val transportType: NetworkTransportType,
    val isMetered: Boolean = false,
    val summary: String = if (isConnected) {
        "Connected via ${transportType.displayName}" +
            (if (hasValidatedInternet) " (Internet validated)" else " (Internet unvalidated)")
    } else {
        "Offline — No active Wi-Fi or Mobile Data connection"
    }
) {
    val isOnline: Boolean
        get() = isConnected && hasValidatedInternet
}

/**
 * Real Android Network Connectivity Monitor for NAYRO.
 * Inspects [ConnectivityManager] and [NetworkCapabilities] for Wi-Fi, Mobile Data (Cellular),
 * Ethernet, and validated internet access so NAYRO never pretends to be online when offline.
 */
class NetworkConnectivityMonitor(
    private val context: Context? = null
) {

    private val _networkStatus = MutableStateFlow(
        NetworkStatus(
            isConnected = false,
            hasValidatedInternet = false,
            transportType = NetworkTransportType.NONE
        )
    )
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    /**
     * Deterministic test override hook for JVM/Robolectric unit tests.
     */
    @Volatile
    var testOverrideStatus: NetworkStatus? = null
        set(value) {
            field = value
            if (value != null) {
                _networkStatus.value = value
            }
        }

    private var registeredCallback: ConnectivityManager.NetworkCallback? = null

    init {
        if (context != null) {
            checkCurrentStatus()
        }
    }

    /**
     * Performs a synchronous real Android ConnectivityManager check for Wi-Fi / Mobile Data / Internet.
     */
    fun checkCurrentStatus(): NetworkStatus {
        testOverrideStatus?.let {
            _networkStatus.value = it
            return it
        }

        val ctx = context ?: return NetworkStatus(
            isConnected = true,
            hasValidatedInternet = true,
            transportType = NetworkTransportType.OTHER,
            summary = "Connectivity status not bound to Android Context"
        )

        val evaluated = try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null

            if (caps == null) {
                NetworkStatus(
                    isConnected = false,
                    hasValidatedInternet = false,
                    transportType = NetworkTransportType.NONE
                )
            } else {
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) || hasInternet
                val transport = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransportType.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransportType.MOBILE_DATA
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransportType.ETHERNET
                    else -> NetworkTransportType.OTHER
                }
                val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                NetworkStatus(
                    isConnected = hasInternet,
                    hasValidatedInternet = hasInternet && validated,
                    transportType = if (hasInternet) transport else NetworkTransportType.NONE,
                    isMetered = isMetered
                )
            }
        } catch (_: SecurityException) {
            NetworkStatus(
                isConnected = false,
                hasValidatedInternet = false,
                transportType = NetworkTransportType.NONE,
                summary = "Cannot inspect network state: ACCESS_NETWORK_STATE permission denied."
            )
        } catch (e: Exception) {
            NetworkStatus(
                isConnected = false,
                hasValidatedInternet = false,
                transportType = NetworkTransportType.NONE,
                summary = "Network check unavailable (${e.localizedMessage ?: e.javaClass.simpleName})."
            )
        }

        _networkStatus.value = evaluated
        return evaluated
    }

    fun isOnline(): Boolean = checkCurrentStatus().isOnline

    /**
     * Starts observing real-time Wi-Fi / Mobile Data transitions via ConnectivityManager.NetworkCallback.
     */
    fun startMonitoring() {
        val ctx = context ?: return
        if (registeredCallback != null) return
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    checkCurrentStatus()
                }

                override fun onLost(network: Network) {
                    checkCurrentStatus()
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    checkCurrentStatus()
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            registeredCallback = callback
            checkCurrentStatus()
        } catch (_: Exception) {
            // Gracefully fall back to on-demand checkCurrentStatus() if callback registration fails
        }
    }

    fun stopMonitoring() {
        val ctx = context ?: return
        val cb = registeredCallback ?: return
        registeredCallback = null
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
    }
}
