package gr.hua.aurora.wifidirect

data class WifiDirectBroadcastEvent(
    val action: String? = null,
    val isWifiP2pEnabled: Boolean? = null,
    val isDiscoveryActive: Boolean? = null,
    val isConnectionEstablished: Boolean? = null
)

interface WifiDirectController {
    interface Listener {
        fun onRuntimeStatusChanged(status: WifiDirectRuntimeStatus)
    }

    fun currentRuntimeStatus(): WifiDirectRuntimeStatus
    fun refreshRuntimeStatus()
    fun refreshConnectionInfo()
    fun startDiscovery()
    fun stopDiscovery()
    fun connectToPeer(peer: WifiDirectPeer)
    fun disconnect()
    fun handleBroadcast(event: WifiDirectBroadcastEvent)
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
}
