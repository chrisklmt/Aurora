package gr.hua.aurora.wifidirect.platform

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.util.Log
import gr.hua.aurora.wifidirect.controller.WifiDirectConnectionSnapshot
import gr.hua.aurora.wifidirect.controller.WifiDirectPeerMapper
import gr.hua.aurora.wifidirect.controller.automatedDiagnosticsWifiDirectDnsSdInstanceName
import gr.hua.aurora.wifidirect.controller.automatedDiagnosticsWifiDirectDnsSdTxtRecord
import gr.hua.aurora.wifidirect.controller.wifiDirectConnectionSnapshot
import gr.hua.aurora.wifidirect.controller.wifiDirectGroupOwnerAddress
import gr.hua.aurora.wifidirect.model.WifiDirectPeer
import gr.hua.aurora.wifidirect.runtime.WifiDirectLocalAddressClassification
import gr.hua.aurora.wifidirect.runtime.WifiDirectLocalDeviceInfo
import gr.hua.aurora.wifidirect.runtime.WifiDirectRolePreference
import gr.hua.aurora.wifidirect.runtime.normalizeWifiDirectDeviceAddress
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketConnectHostOrNull

private const val wifiDirectPlatformClientLogTag = "WifiDirectPlatformClient"

internal interface WifiDirectPlatformClient {
    fun discoverPeers(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    )

    fun stopPeerDiscovery(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    )

    fun requestPeers(
        onSuccess: (List<WifiDirectPeer>) -> Unit,
        onFailure: (String) -> Unit
    )

    fun connectToPeer(
        peer: WifiDirectPeer,
        rolePreference: WifiDirectRolePreference = WifiDirectRolePreference.AUTOMATIC,
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    )

    fun cancelPendingConnection(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    )

    fun disconnectFromPeer(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    )

    fun requestConnectionSnapshot(
        onSuccess: (WifiDirectConnectionSnapshot) -> Unit,
        onFailure: (String) -> Unit
    )

    fun requestLocalDeviceInfo(
        onSuccess: (WifiDirectLocalDeviceInfo) -> Unit,
        onFailure: (String) -> Unit
    )

    fun setDnsSdResponseListeners(
        onServiceAvailable: (String?, String?, WifiDirectPeer) -> Unit,
        onTxtRecordAvailable: (String?, Map<String, String>, WifiDirectPeer) -> Unit,
        onFailure: (String) -> Unit
    )

    fun addLocalDnsSdService(
        instanceName: String = automatedDiagnosticsWifiDirectDnsSdInstanceName,
        serviceType: String,
        txtRecord: Map<String, String>,
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    )

    fun clearLocalDnsSdServices(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    )

    fun addDnsSdServiceRequest(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    )

    fun clearDnsSdServiceRequests(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    )

    fun discoverDnsSdServices(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    )
}

internal class AndroidWifiDirectPlatformClient private constructor(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) : WifiDirectPlatformClient {

    override fun discoverPeers(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        runCatching {
            manager.discoverPeers(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onSuccess()
                    }

                    override fun onFailure(reason: Int) {
                        onFailure(reason)
                    }
                }
            )
        }.getOrElse {
            onFailure(WifiP2pManager.ERROR)
        }
    }

    override fun stopPeerDiscovery(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        runCatching {
            manager.stopPeerDiscovery(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onSuccess()
                    }

                    override fun onFailure(reason: Int) {
                        onFailure(reason)
                    }
                }
            )
        }.getOrElse {
            onFailure(WifiP2pManager.ERROR)
        }
    }

    override fun requestPeers(
        onSuccess: (List<WifiDirectPeer>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        runCatching {
            manager.requestPeers(channel) { peerList: WifiP2pDeviceList ->
                onSuccess(WifiDirectPeerMapper.mapDeviceList(peerList))
            }
        }.getOrElse { error ->
            onFailure(error::class.java.simpleName)
        }
    }

    override fun connectToPeer(
        peer: WifiDirectPeer,
        rolePreference: WifiDirectRolePreference,
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        val deviceAddress = peer.deviceAddress?.trim()?.takeIf { it.isNotEmpty() }
        val requestedGroupOwnerIntent = wifiDirectGroupOwnerIntentOrNull(rolePreference)
        safeWifiDirectPlatformClientLogDebug(
            "connectToPeer invoked: ${wifiDirectConnectRequestDebugText(peer, rolePreference)} " +
                if (requestedGroupOwnerIntent == null) {
                    "groupOwnerIntent left default"
                } else {
                    "groupOwnerIntent set=$requestedGroupOwnerIntent"
                }
        )
        if (deviceAddress == null) {
            safeWifiDirectPlatformClientLogDebug(
                "connectToPeer blocked: ${wifiDirectConnectRequestDebugText(peer, rolePreference)} reason=missing-device-address"
            )
            onFailure(WifiP2pManager.ERROR)
            return
        }

        runCatching {
            manager.connect(
                channel,
                WifiP2pConfig().apply {
                    this.deviceAddress = deviceAddress
                    requestedGroupOwnerIntent?.let { groupOwnerIntent ->
                        this.groupOwnerIntent = groupOwnerIntent
                    }
                },
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onSuccess()
                    }

                    override fun onFailure(reason: Int) {
                        onFailure(reason)
                    }
                }
            )
        }.getOrElse {
            onFailure(WifiP2pManager.ERROR)
        }
    }

    override fun cancelPendingConnection(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        runCatching {
            manager.cancelConnect(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onSuccess()
                    }

                    override fun onFailure(reason: Int) {
                        onFailure(reason)
                    }
                }
            )
        }.getOrElse {
            onFailure(WifiP2pManager.ERROR)
        }
    }

    override fun disconnectFromPeer(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        runCatching {
            manager.removeGroup(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onSuccess()
                    }

                    override fun onFailure(reason: Int) {
                        onFailure(reason)
                    }
                }
            )
        }.getOrElse {
            onFailure(WifiP2pManager.ERROR)
        }
    }

    override fun requestConnectionSnapshot(
        onSuccess: (WifiDirectConnectionSnapshot) -> Unit,
        onFailure: (String) -> Unit
    ) {
        runCatching {
            manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                val baseSnapshot = wifiDirectConnectionSnapshot(info)
                runCatching {
                    manager.requestGroupInfo(channel) { group: WifiP2pGroup? ->
                        val fallbackOwnerHost = wifiDirectSocketConnectHostOrNull(
                            wifiDirectGroupOwnerAddress(group)
                        )
                        onSuccess(
                            baseSnapshot.copy(
                                groupOwnerAddress = baseSnapshot.groupOwnerAddress
                                    ?: fallbackOwnerHost
                            )
                        )
                    }
                }.getOrElse {
                    onSuccess(baseSnapshot)
                }
            }
        }.getOrElse { error ->
            onFailure(error::class.java.simpleName)
        }
    }

    override fun requestLocalDeviceInfo(
        onSuccess: (WifiDirectLocalDeviceInfo) -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onFailure("requestDeviceInfo unavailable on this Android version.")
            return
        }

        runCatching {
            manager.requestDeviceInfo(channel) { device ->
                val deviceName = device?.deviceName?.trim()?.takeIf { it.isNotEmpty() }
                val deviceAddress = device?.deviceAddress?.trim()?.takeIf { it.isNotEmpty() }
                onSuccess(
                    WifiDirectLocalDeviceInfo(
                        deviceName = deviceName,
                        deviceAddress = deviceAddress,
                        lastError = when {
                            deviceAddress == null ->
                                "Wi-Fi Direct local device address unavailable."

                            normalizeWifiDirectDeviceAddress(deviceAddress) == null ->
                                "Wi-Fi Direct local device address invalid: $deviceAddress"

                            WifiDirectLocalAddressClassification.ANONYMIZED ==
                                WifiDirectLocalDeviceInfo(
                                    deviceName = deviceName,
                                    deviceAddress = deviceAddress
                                ).addressClassification ->
                                "Wi-Fi Direct local device address is anonymized."

                            else -> null
                        }
                    )
                )
            }
        }.getOrElse { error ->
            onFailure(error::class.java.simpleName)
        }
    }

    override fun setDnsSdResponseListeners(
        onServiceAvailable: (String?, String?, WifiDirectPeer) -> Unit,
        onTxtRecordAvailable: (String?, Map<String, String>, WifiDirectPeer) -> Unit,
        onFailure: (String) -> Unit
    ) {
        runCatching {
            manager.setDnsSdResponseListeners(
                channel,
                WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, device ->
                    device?.let { srcDevice ->
                        onServiceAvailable(
                            instanceName,
                            registrationType,
                            WifiDirectPeerMapper.mapDevice(srcDevice)
                        )
                    }
                },
                WifiP2pManager.DnsSdTxtRecordListener { fullDomain, txtRecord, device ->
                    device?.let { srcDevice ->
                        onTxtRecordAvailable(
                            fullDomain,
                            txtRecord.orEmpty(),
                            WifiDirectPeerMapper.mapDevice(srcDevice)
                        )
                    }
                }
            )
        }.getOrElse { error ->
            onFailure(error::class.java.simpleName)
        }
    }

    override fun addLocalDnsSdService(
        instanceName: String,
        serviceType: String,
        txtRecord: Map<String, String>,
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        runCatching {
            manager.addLocalService(
                channel,
                WifiP2pDnsSdServiceInfo.newInstance(
                    instanceName,
                    serviceType,
                    HashMap(txtRecord)
                ),
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onSuccess()
                    }

                    override fun onFailure(reason: Int) {
                        onFailure(reason)
                    }
                }
            )
        }.getOrElse {
            onFailure(WifiP2pManager.ERROR)
        }
    }

    override fun clearLocalDnsSdServices(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        runCatching {
            manager.clearLocalServices(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onSuccess()
                    }

                    override fun onFailure(reason: Int) {
                        onFailure(reason)
                    }
                }
            )
        }.getOrElse {
            onFailure(WifiP2pManager.ERROR)
        }
    }

    override fun addDnsSdServiceRequest(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        runCatching {
            manager.addServiceRequest(
                channel,
                WifiP2pDnsSdServiceRequest.newInstance(),
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onSuccess()
                    }

                    override fun onFailure(reason: Int) {
                        onFailure(reason)
                    }
                }
            )
        }.getOrElse {
            onFailure(WifiP2pManager.ERROR)
        }
    }

    override fun clearDnsSdServiceRequests(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        runCatching {
            manager.clearServiceRequests(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onSuccess()
                    }

                    override fun onFailure(reason: Int) {
                        onFailure(reason)
                    }
                }
            )
        }.getOrElse {
            onFailure(WifiP2pManager.ERROR)
        }
    }

    override fun discoverDnsSdServices(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        runCatching {
            manager.discoverServices(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onSuccess()
                    }

                    override fun onFailure(reason: Int) {
                        onFailure(reason)
                    }
                }
            )
        }.getOrElse {
            onFailure(WifiP2pManager.ERROR)
        }
    }

    companion object {
        fun create(context: Context): WifiDirectPlatformClient? {
            val appContext = context.applicationContext
            val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: return null
            val channel = runCatching {
                manager.initialize(appContext, Looper.getMainLooper(), null)
            }.getOrNull() ?: return null
            return AndroidWifiDirectPlatformClient(
                manager = manager,
                channel = channel
            )
        }
    }
}

internal fun wifiDirectGroupOwnerIntentOrNull(
    preference: WifiDirectRolePreference
): Int? {
    return when (preference) {
        WifiDirectRolePreference.AUTOMATIC -> null
        WifiDirectRolePreference.PREFER_GROUP_OWNER -> 15
        WifiDirectRolePreference.PREFER_CLIENT -> 0
    }
}

internal fun wifiDirectGroupOwnerIntentDebugLabel(
    preference: WifiDirectRolePreference
): String {
    return wifiDirectGroupOwnerIntentOrNull(preference)?.toString() ?: "default"
}

internal fun wifiDirectConnectRequestDebugText(
    peer: WifiDirectPeer,
    rolePreference: WifiDirectRolePreference
): String {
    val peerName = peer.deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
    val peerAddress = peer.deviceAddress?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
    return "peerName=$peerName peerAddress=$peerAddress " +
        "preference=${rolePreference.name.lowercase()} " +
        "groupOwnerIntent=${wifiDirectGroupOwnerIntentDebugLabel(rolePreference)}"
}

private fun safeWifiDirectPlatformClientLogDebug(
    message: String
) {
    runCatching {
        Log.d(wifiDirectPlatformClientLogTag, message)
    }
}
