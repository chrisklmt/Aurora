package gr.hua.aurora.wifidirect

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build

class AndroidWifiDirectController internal constructor(
    private val permissionStatusReader: () -> WifiDirectPermissionStatus,
    private val fallbackPermissionStatus: () -> WifiDirectPermissionStatus,
    private val platformClient: WifiDirectPlatformClient?,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : WifiDirectController {

    constructor(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ) : this(
        permissionStatusReader = {
            WifiDirectPermissionStatusReader.read(
                context = context.applicationContext,
                sdkInt = sdkInt
            )
        },
        fallbackPermissionStatus = {
            fallbackWifiDirectPermissionStatus(sdkInt)
        },
        platformClient = AndroidWifiDirectPlatformClient.create(context.applicationContext)
    )

    private val listeners = linkedSetOf<WifiDirectController.Listener>()
    private var latestWifiP2pEnabled: Boolean? = null
    private var discoveryState = WifiDirectDiscoveryState.INACTIVE
    private var peers = emptyList<WifiDirectPeer>()
    private var lastError: String? = null
    private var lastUpdatedAtMillis: Long? = null

    override fun currentRuntimeStatus(): WifiDirectRuntimeStatus {
        return buildRuntimeStatus()
    }

    override fun refreshRuntimeStatus() {
        emitCurrentRuntimeStatus()
    }

    override fun startDiscovery() {
        val permissionStatus = readPermissionStatusSafely()
        val blockReason = wifiDirectDiscoveryBlockedReason(permissionStatus)
        if (blockReason != null) {
            discoveryState = WifiDirectDiscoveryState.INACTIVE
            peers = emptyList()
            setLastError(blockReason)
            emit(permissionStatus)
            return
        }
        val client = platformClient
        if (client == null) {
            discoveryState = WifiDirectDiscoveryState.INACTIVE
            peers = emptyList()
            setLastError("Wi-Fi Direct unsupported on this device.")
            emit(permissionStatus)
            return
        }

        client.discoverPeers(
            onSuccess = {
                discoveryState = WifiDirectDiscoveryState.ACTIVE
                clearLastError()
                touch()
                emitCurrentRuntimeStatus()
                requestPeers(client)
            },
            onFailure = { reason ->
                discoveryState = WifiDirectDiscoveryState.INACTIVE
                peers = emptyList()
                setLastError("Wi-Fi Direct discovery failed: ${wifiDirectFailureLabel(reason)}")
                emitCurrentRuntimeStatus()
            }
        )
    }

    override fun stopDiscovery() {
        val client = platformClient
        if (client == null) {
            discoveryState = WifiDirectDiscoveryState.INACTIVE
            peers = emptyList()
            clearLastError()
            emitCurrentRuntimeStatus()
            return
        }

        client.stopPeerDiscovery(
            onSuccess = {
                discoveryState = WifiDirectDiscoveryState.INACTIVE
                peers = emptyList()
                clearLastError()
                touch()
                emitCurrentRuntimeStatus()
            },
            onFailure = { reason ->
                discoveryState = WifiDirectDiscoveryState.INACTIVE
                peers = emptyList()
                setLastError("Wi-Fi Direct discovery stop failed: ${wifiDirectFailureLabel(reason)}")
                emitCurrentRuntimeStatus()
            }
        )
    }

    override fun handleBroadcast(event: WifiDirectBroadcastEvent) {
        when (event.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                latestWifiP2pEnabled = event.isWifiP2pEnabled
                emitCurrentRuntimeStatus()
            }
            WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                event.isDiscoveryActive?.let { isActive ->
                    discoveryState = if (isActive) {
                        WifiDirectDiscoveryState.ACTIVE
                    } else {
                        WifiDirectDiscoveryState.INACTIVE
                    }
                    touch()
                }
                if (event.isDiscoveryActive == true && platformClient != null) {
                    requestPeers(platformClient)
                } else {
                    emitCurrentRuntimeStatus()
                }
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                platformClient?.let(::requestPeers) ?: emitCurrentRuntimeStatus()
            }
            else -> {
                emitCurrentRuntimeStatus()
            }
        }
    }

    override fun addListener(listener: WifiDirectController.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: WifiDirectController.Listener) {
        listeners -= listener
    }

    private fun requestPeers(client: WifiDirectPlatformClient) {
        client.requestPeers(
            onSuccess = { discoveredPeers ->
                peers = WifiDirectPeerMapper.normalizePeers(discoveredPeers)
                clearLastError()
                touch()
                emitCurrentRuntimeStatus()
            },
            onFailure = { reason ->
                peers = emptyList()
                setLastError("Wi-Fi Direct peers unavailable: $reason")
                emitCurrentRuntimeStatus()
            }
        )
    }

    private fun buildRuntimeStatus(): WifiDirectRuntimeStatus {
        val permissionStatus = readPermissionStatusSafely()
        val blockReason = wifiDirectDiscoveryBlockedReason(permissionStatus)
        if (blockReason != null) {
            if (discoveryState == WifiDirectDiscoveryState.ACTIVE || peers.isNotEmpty()) {
                setLastError(blockReason)
            }
            discoveryState = WifiDirectDiscoveryState.INACTIVE
            peers = emptyList()
        }

        return buildWifiDirectRuntimeStatus(
            permissionStatus = permissionStatus,
            discoveryState = discoveryState,
            peers = peers,
            lastError = lastError,
            lastUpdatedAtMillis = lastUpdatedAtMillis
        )
    }

    private fun emitCurrentRuntimeStatus() {
        emit(buildRuntimeStatus())
    }

    private fun emit(
        permissionStatus: WifiDirectPermissionStatus
    ) {
        emit(
            buildWifiDirectRuntimeStatus(
                permissionStatus = permissionStatus,
                discoveryState = discoveryState,
                peers = peers,
                lastError = lastError,
                lastUpdatedAtMillis = lastUpdatedAtMillis
            )
        )
    }

    private fun emit(status: WifiDirectRuntimeStatus) {
        listeners.forEach { listener ->
            listener.onRuntimeStatusChanged(status)
        }
    }

    private fun readPermissionStatusSafely(): WifiDirectPermissionStatus {
        val baseStatus = runCatching(permissionStatusReader).getOrElse { error ->
            setLastError(wifiDirectStatusUnavailableReason(error))
            fallbackPermissionStatus()
        }
        return wifiDirectPermissionStatusWithP2pState(
            status = baseStatus,
            isWifiP2pEnabled = latestWifiP2pEnabled
        )
    }

    private fun setLastError(error: String) {
        lastError = error
        touch()
    }

    private fun clearLastError() {
        if (lastError != null) {
            lastError = null
            touch()
        }
    }

    private fun touch() {
        lastUpdatedAtMillis = nowMillis()
    }
}
