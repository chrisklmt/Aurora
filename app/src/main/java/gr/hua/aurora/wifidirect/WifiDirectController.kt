package gr.hua.aurora.wifidirect

data class WifiDirectBroadcastEvent(
    val action: String? = null,
    val isWifiP2pEnabled: Boolean? = null,
    val isDiscoveryActive: Boolean? = null
)

interface WifiDirectController {
    interface Listener {
        fun onRuntimeStatusChanged(status: WifiDirectRuntimeStatus)
    }

    fun currentRuntimeStatus(): WifiDirectRuntimeStatus
    fun refreshRuntimeStatus()
    fun startDiscovery()
    fun stopDiscovery()
    fun handleBroadcast(event: WifiDirectBroadcastEvent)
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
}
