package android.net.nsd

import java.net.InetAddress

// CoopLan's LAN discovery (NsdCoopLan): the shapes it compiles against. The preview never finds anyone.
class NsdServiceInfo {
    var serviceName: String = ""
    var serviceType: String = ""
    var port: Int = 0
        private set
    fun setPort(p: Int) { port = p }
    var host: InetAddress? = null
    val attributes: Map<String, ByteArray> = emptyMap()
    fun setAttribute(key: String, value: String) {}
}

class NsdManager {
    interface RegistrationListener {
        fun onServiceRegistered(info: NsdServiceInfo)
        fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int)
        fun onServiceUnregistered(info: NsdServiceInfo)
        fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int)
    }

    interface DiscoveryListener {
        fun onDiscoveryStarted(serviceType: String)
        fun onDiscoveryStopped(serviceType: String)
        fun onStartDiscoveryFailed(serviceType: String, errorCode: Int)
        fun onStopDiscoveryFailed(serviceType: String, errorCode: Int)
        fun onServiceFound(info: NsdServiceInfo)
        fun onServiceLost(info: NsdServiceInfo)
    }

    interface ResolveListener {
        fun onResolveFailed(info: NsdServiceInfo, errorCode: Int)
        fun onServiceResolved(info: NsdServiceInfo)
    }

    fun registerService(info: NsdServiceInfo, protocol: Int, listener: RegistrationListener) {}
    fun unregisterService(listener: RegistrationListener) {}
    fun discoverServices(type: String, protocol: Int, listener: DiscoveryListener) {}
    fun stopServiceDiscovery(listener: DiscoveryListener) {}
    fun resolveService(info: NsdServiceInfo, listener: ResolveListener) {}

    companion object {
        const val PROTOCOL_DNS_SD = 1
    }
}
