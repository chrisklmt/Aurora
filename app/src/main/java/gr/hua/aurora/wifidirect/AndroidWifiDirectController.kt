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
        val blockReason = wifiDirectDiscoveryBlockedReason(permissionStatus)
        val normalizedPeer = WifiDirectPeerMapper.normalizePeer(peer)
        if (blockReason != null) {
            connectionStatus = wifiDirectConnectionFailureStatus(
                current = connectionStatus,
                targetPeer = normalizedPeer,
                reason = blockReason
            )
            emit(permissionStatus)
            return
        }
        val client = platformClient
        if (client == null) {
            connectionStatus = wifiDirectConnectionFailureStatus(
                current = connectionStatus,
                targetPeer = normalizedPeer,
                reason = "Wi-Fi Direct unsupported on this device."
            )
            emit(permissionStatus)
            return
        }
        val targetPeer = visiblePeerOrNull(normalizedPeer)
        if (targetPeer == null) {
            connectionStatus = wifiDirectConnectionFailureStatus(
                current = connectionStatus,
                targetPeer = normalizedPeer,
                reason = "Selected Wi-Fi Direct peer is no longer visible."
            )
            emit(permissionStatus)
            return
        }
        if (targetPeer.deviceAddress.isNullOrBlank()) {
            connectionStatus = wifiDirectConnectionFailureStatus(
                current = connectionStatus,
                targetPeer = targetPeer,
                reason = "Selected Wi-Fi Direct peer address unavailable."
            )
            emit(permissionStatus)
            return
        }

        when (connectionStatus.state) {
            WifiDirectConnectionState.CONNECTING -> {
                if (wifiDirectPeerMatches(connectionStatus.targetPeer, targetPeer)) {
                    emit(permissionStatus)
                } else {
                    connectionStatus = wifiDirectConnectionFailureStatus(
                        current = connectionStatus,
                        targetPeer = targetPeer,
                        reason = "Wi-Fi Direct connection already in progress."
                    )
                    emit(permissionStatus)
                }
                return
            }
            WifiDirectConnectionState.CONNECTED -> {
                if (wifiDirectPeerMatches(connectionStatus.targetPeer, targetPeer)) {
                    clearConnectionError()
                    requestConnectionSnapshot(client)
                } else {
                    connectionStatus = wifiDirectConnectionFailureStatus(
                        current = connectionStatus,
                        targetPeer = targetPeer,
                        reason = "Disconnect current Wi-Fi Direct peer first."
                    )
                    emit(permissionStatus)
                }
                return
            }
            WifiDirectConnectionState.DISCONNECTING -> {
                connectionStatus = wifiDirectConnectionFailureStatus(
                    current = connectionStatus,
                    targetPeer = targetPeer,
                    reason = "Wi-Fi Direct disconnect already in progress."
                )
                emit(permissionStatus)
                return
            }
            WifiDirectConnectionState.DISCONNECTED,
            WifiDirectConnectionState.FAILED -> Unit
        }

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

    private fun visiblePeerOrNull(targetPeer: WifiDirectPeer): WifiDirectPeer? {
        return peers.firstOrNull { peer ->
            wifiDirectPeerMatches(peer, targetPeer)
        }
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

internal fun wifiDirectConnectionStatusFromSnapshot(
    current: WifiDirectConnectionStatus,
    snapshot: WifiDirectConnectionSnapshot
): WifiDirectConnectionStatus {
    val resolvedState = when (snapshot.groupFormed) {
        WifiDirectGroupFormedState.YES -> WifiDirectConnectionState.CONNECTED
        WifiDirectGroupFormedState.NO -> when (current.state) {
            WifiDirectConnectionState.CONNECTING -> WifiDirectConnectionState.CONNECTING
            WifiDirectConnectionState.DISCONNECTING -> WifiDirectConnectionState.DISCONNECTING
            else -> WifiDirectConnectionState.DISCONNECTED
        }
        WifiDirectGroupFormedState.UNKNOWN -> when (current.state) {
            WifiDirectConnectionState.CONNECTING,
            WifiDirectConnectionState.DISCONNECTING -> current.state
            WifiDirectConnectionState.CONNECTED -> WifiDirectConnectionState.CONNECTED
            else -> WifiDirectConnectionState.DISCONNECTED
        }
    }
    return current.copy(
        state = resolvedState,
        groupFormed = snapshot.groupFormed,
        role = snapshot.role,
        groupOwnerAddress = snapshot.groupOwnerAddress,
        lastError = if (resolvedState == WifiDirectConnectionState.CONNECTED) {
            null
        } else {
            current.lastError
        }
    )
}

internal fun wifiDirectConnectionFailureStatus(
    current: WifiDirectConnectionStatus,
    targetPeer: WifiDirectPeer?,
    reason: String
): WifiDirectConnectionStatus {
    return current.copy(
        state = WifiDirectConnectionState.FAILED,
        targetPeer = targetPeer ?: current.targetPeer,
        groupFormed = WifiDirectGroupFormedState.UNKNOWN,
        role = WifiDirectConnectionRole.UNKNOWN,
        groupOwnerAddress = null,
        lastError = reason
    )
}

internal fun wifiDirectDisconnectedStatus(
    current: WifiDirectConnectionStatus,
    keepLastError: Boolean = false,
    lastError: String? = current.lastError
): WifiDirectConnectionStatus {
    return current.copy(
        state = WifiDirectConnectionState.DISCONNECTED,
        targetPeer = null,
        groupFormed = WifiDirectGroupFormedState.UNKNOWN,
        role = WifiDirectConnectionRole.UNKNOWN,
        groupOwnerAddress = null,
        lastError = if (keepLastError) lastError else null
    )
}

internal fun wifiDirectPeerMatches(
    left: WifiDirectPeer?,
    right: WifiDirectPeer?
): Boolean {
    if (left == null || right == null) return false
    val leftAddress = left.deviceAddress?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    val rightAddress = right.deviceAddress?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    if (leftAddress != null && rightAddress != null) {
        return leftAddress == rightAddress
    }
    val leftName = left.deviceName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val rightName = right.deviceName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    return leftName != null && leftName == rightName
}
