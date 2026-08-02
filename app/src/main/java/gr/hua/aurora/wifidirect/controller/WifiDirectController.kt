package gr.hua.aurora.wifidirect.controller

import gr.hua.aurora.wifidirect.model.WifiDirectPeer
import gr.hua.aurora.wifidirect.runtime.WifiDirectRolePreference
import gr.hua.aurora.wifidirect.runtime.WifiDirectRuntimeStatus

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
    fun registerAutomatedDiagnosticsService(
        correlationToken: String,
        deviceNameHint: String? = null
    )
    fun startAutomatedDiagnosticsServiceDiscovery()
    fun clearAutomatedDiagnosticsServiceDiscovery()
    fun connectToPeer(
        peer: WifiDirectPeer,
        rolePreference: WifiDirectRolePreference = WifiDirectRolePreference.AUTOMATIC
    )
    fun disconnect()
    fun handleBroadcast(event: WifiDirectBroadcastEvent)
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
}
