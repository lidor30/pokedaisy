package android.net.wifi

class WifiManager {
    class MulticastLock {
        val isHeld = false
        fun setReferenceCounted(on: Boolean) {}
        fun acquire() {}
        fun release() {}
    }

    fun createMulticastLock(tag: String) = MulticastLock()
}
