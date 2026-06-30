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
    private var connectionStatus = WifiDirectConnectionStatus()
    private var peers = emptyList<WifiDirectPeer>()
    private var lastError: String? = null
    private var lastUpdatedAtMillis: Long? = null

    override fun currentRuntimeStatus(): WifiDirectRuntimeStatus {
        return buildRuntimeStatus()
    }

    override fun refreshRuntimeStatus() {
        emitCurrentRuntimeStatus()
        refreshConnectionInfo()
    }

    override fun refreshConnectionInfo() {
        val permissionStatus = readPermissionStatusSafely()
        if (wifiDirectDiscoveryBlockedReason(permissionStatus) != null) {
            emit(permissionStatus)
            return
        }
        val client = platformClient ?: run {
            emitCurrentRuntimeStatus()
            return
        }
        requestConnectionSnapshot(client)
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

    override fun connectToPeer(peer: WifiDirectPeer) {
        val permissionStatus = readPermissionStatusSafely()
        val decision = wifiDirectConnectCommandDecision(
            permissionStatus = permissionStatus,
            platformClientAvailable = platformClient != null,
            currentConnectionStatus = connectionStatus,
            visiblePeers = peers,
            requestedPeer = peer
        )
        when (decision) {
            is WifiDirectConnectCommandDecision.Blocked -> {
                connectionStatus = wifiDirectConnectionFailureStatus(
                    current = connectionStatus,
                    targetPeer = decision.targetPeer,
                    reason = decision.reason
                )
                emit(permissionStatus)
                return
            }
            is WifiDirectConnectCommandDecision.Allowed -> {
                if (connectionStatus.state == WifiDirectConnectionState.CONNECTED &&
                    wifiDirectPeerMatches(connectionStatus.targetPeer, decision.targetPeer)
                ) {
                    clearConnectionError()
                    platformClient?.let(::requestConnectionSnapshot)
                    return
                }
                if (connectionStatus.state == WifiDirectConnectionState.CONNECTING &&
                    wifiDirectPeerMatches(connectionStatus.targetPeer, decision.targetPeer)
                ) {
                    emit(permissionStatus)
                    return
                }
            }
        }
        val client = platformClient ?: return
        val targetPeer = (decision as WifiDirectConnectCommandDecision.Allowed).targetPeer

        connectionStatus = connectionStatus.copy(
            state = WifiDirectConnectionState.CONNECTING,
            targetPeer = targetPeer,
            groupFormed = WifiDirectGroupFormedState.UNKNOWN,
            role = WifiDirectConnectionRole.UNKNOWN,
            groupOwnerAddress = null,
            lastError = null
        )
        touch()
        emit(permissionStatus)

        client.connectToPeer(
            peer = targetPeer,
            onSuccess = {
                clearConnectionError()
                requestConnectionSnapshot(client)
            },
            onFailure = { reason ->
                connectionStatus = wifiDirectConnectionFailureStatus(
                    current = connectionStatus,
                    targetPeer = targetPeer,
                    reason = "Wi-Fi Direct connect failed: ${wifiDirectFailureLabel(reason)}"
                )
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

    override fun disconnect() {
        val permissionStatus = readPermissionStatusSafely()
        val client = platformClient
        if (client == null) {
            connectionStatus = wifiDirectDisconnectedStatus(
                current = connectionStatus,
                keepLastError = true
            )
            emit(permissionStatus)
            return
        }

        when (connectionStatus.state) {
            WifiDirectConnectionState.CONNECTING -> {
                connectionStatus = connectionStatus.copy(
                    state = WifiDirectConnectionState.DISCONNECTING,
                    groupFormed = WifiDirectGroupFormedState.UNKNOWN,
                    role = WifiDirectConnectionRole.UNKNOWN,
                    groupOwnerAddress = null,
                    lastError = null
                )
                touch()
                emit(permissionStatus)
                client.cancelPendingConnection(
                    onSuccess = {
                        connectionStatus = wifiDirectDisconnectedStatus(connectionStatus)
                        emitCurrentRuntimeStatus()
                    },
                    onFailure = { reason ->
                        connectionStatus = wifiDirectConnectionFailureStatus(
                            current = connectionStatus,
                            targetPeer = connectionStatus.targetPeer,
                            reason = "Wi-Fi Direct cancel failed: ${wifiDirectFailureLabel(reason)}"
                        )
                        emitCurrentRuntimeStatus()
                    }
                )
            }
            WifiDirectConnectionState.CONNECTED -> {
                connectionStatus = connectionStatus.copy(
                    state = WifiDirectConnectionState.DISCONNECTING,
                    lastError = null
                )
                touch()
                emit(permissionStatus)
                client.disconnectFromPeer(
                    onSuccess = {
                        connectionStatus = wifiDirectDisconnectedStatus(connectionStatus)
                        emitCurrentRuntimeStatus()
                    },
                    onFailure = { reason ->
                        connectionStatus = wifiDirectConnectionFailureStatus(
                            current = connectionStatus,
                            targetPeer = connectionStatus.targetPeer,
                            reason = "Wi-Fi Direct disconnect failed: ${wifiDirectFailureLabel(reason)}"
                        )
                        emitCurrentRuntimeStatus()
                    }
                )
            }
            WifiDirectConnectionState.FAILED -> {
                connectionStatus = wifiDirectDisconnectedStatus(
                    current = connectionStatus,
                    keepLastError = true
                )
                emit(permissionStatus)
            }
            WifiDirectConnectionState.DISCONNECTING,
            WifiDirectConnectionState.DISCONNECTED -> {
                emit(permissionStatus)
            }
        }
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
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                when (event.isConnectionEstablished) {
                    true -> platformClient?.let(::requestConnectionSnapshot) ?: emitCurrentRuntimeStatus()
                    false -> {
                        connectionStatus = wifiDirectDisconnectedStatus(connectionStatus)
                        emitCurrentRuntimeStatus()
                    }
                    null -> platformClient?.let(::requestConnectionSnapshot) ?: emitCurrentRuntimeStatus()
                }
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

    private fun requestConnectionSnapshot(client: WifiDirectPlatformClient) {
        client.requestConnectionSnapshot(
            onSuccess = { snapshot ->
                connectionStatus = wifiDirectConnectionStatusFromSnapshot(
                    current = connectionStatus,
                    snapshot = snapshot
                )
                if (connectionStatus.state == WifiDirectConnectionState.CONNECTED) {
                    clearConnectionError()
                } else {
                    touch()
                }
                emitCurrentRuntimeStatus()
            },
            onFailure = { reason ->
                connectionStatus = wifiDirectConnectionFailureStatus(
                    current = connectionStatus,
                    targetPeer = connectionStatus.targetPeer,
                    reason = "Wi-Fi Direct connection info unavailable: $reason"
                )
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
            if (connectionStatus.state != WifiDirectConnectionState.DISCONNECTED &&
                connectionStatus.state != WifiDirectConnectionState.FAILED
            ) {
                connectionStatus = wifiDirectDisconnectedStatus(
                    current = connectionStatus,
                    keepLastError = true,
                    lastError = blockReason
                )
            }
        }

        return buildWifiDirectRuntimeStatus(
            permissionStatus = permissionStatus,
            discoveryState = discoveryState,
            connectionStatus = connectionStatus,
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
                connectionStatus = connectionStatus,
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

    private fun clearConnectionError() {
        if (connectionStatus.lastError != null) {
            connectionStatus = connectionStatus.copy(lastError = null)
            touch()
        }
    }

    private fun touch() {
        lastUpdatedAtMillis = nowMillis()
    }
}
