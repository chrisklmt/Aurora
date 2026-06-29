package gr.hua.aurora.wifidirect

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper

internal data class WifiDirectConnectionSnapshot(
    val groupFormed: WifiDirectGroupFormedState = WifiDirectGroupFormedState.UNKNOWN,
    val role: WifiDirectConnectionRole = WifiDirectConnectionRole.UNKNOWN,
    val groupOwnerAddress: String? = null
)

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
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        val deviceAddress = peer.deviceAddress?.trim()?.takeIf { it.isNotEmpty() }
        if (deviceAddress == null) {
            onFailure(WifiP2pManager.ERROR)
            return
        }

        runCatching {
            manager.connect(
                channel,
                WifiP2pConfig().apply {
                    this.deviceAddress = deviceAddress
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
                        onSuccess(
                            baseSnapshot.copy(
                                groupOwnerAddress = wifiDirectGroupOwnerAddress(group)
                                    ?: baseSnapshot.groupOwnerAddress
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

internal fun wifiDirectConnectionSnapshot(
    groupFormed: Boolean?,
    isGroupOwner: Boolean?,
    groupOwnerAddress: String?
): WifiDirectConnectionSnapshot {
    val normalizedGroupOwnerAddress = groupOwnerAddress?.trim()?.takeIf { it.isNotEmpty() }
    val groupFormedState = when (groupFormed) {
        true -> WifiDirectGroupFormedState.YES
        false -> WifiDirectGroupFormedState.NO
        null -> WifiDirectGroupFormedState.UNKNOWN
    }
    val role = when {
        groupFormed != true -> WifiDirectConnectionRole.UNKNOWN
        isGroupOwner == true -> WifiDirectConnectionRole.GROUP_OWNER
        isGroupOwner == false -> WifiDirectConnectionRole.CLIENT
        else -> WifiDirectConnectionRole.UNKNOWN
    }
    return WifiDirectConnectionSnapshot(
        groupFormed = groupFormedState,
        role = role,
        groupOwnerAddress = normalizedGroupOwnerAddress
    )
}

internal fun wifiDirectConnectionSnapshot(
    info: WifiP2pInfo
): WifiDirectConnectionSnapshot {
    return wifiDirectConnectionSnapshot(
        groupFormed = info.groupFormed,
        isGroupOwner = if (info.groupFormed) info.isGroupOwner else null,
        groupOwnerAddress = info.groupOwnerAddress?.hostAddress
    )
}

internal fun wifiDirectGroupOwnerAddress(
    group: WifiP2pGroup?
): String? {
    return group?.owner?.deviceAddress?.trim()?.takeIf { it.isNotEmpty() }
}
