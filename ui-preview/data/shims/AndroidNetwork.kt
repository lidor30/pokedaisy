package android.net

/** The co-op session's Wi-Fi check (CoopSession.takeMulticast): never reached in the preview, which runs no endpoint. */
class Network

class NetworkCapabilities {
    fun hasTransport(t: Int) = false

    companion object { const val TRANSPORT_WIFI = 1 }
}

class ConnectivityManager {
    val activeNetwork: Network? get() = null
    fun getNetworkCapabilities(n: Network?): NetworkCapabilities? = null
}
