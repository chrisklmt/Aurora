package gr.hua.aurora.wifidirect.controller

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import gr.hua.aurora.wifidirect.*
import gr.hua.aurora.wifidirect.model.WifiDirectPeer
import gr.hua.aurora.wifidirect.platform.AndroidWifiDirectPlatformClient
import gr.hua.aurora.wifidirect.platform.WifiDirectPlatformClient
import gr.hua.aurora.wifidirect.platform.wifiDirectConnectRequestDebugText
import gr.hua.aurora.wifidirect.runtime.*

private const val androidWifiDirectControllerLogTag = "AndroidWifiDirectController"

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
    private var localDeviceInfo = WifiDirectLocalDeviceInfo()
    private var dnsSdDiagnostics = WifiDirectDnsSdDiagnostics()
    private var peers = emptyList<WifiDirectPeer>()
    private var lastError: String? = null
    private var lastUpdatedAtMillis: Long? = null
    private val dnsSdResponsesByKey = linkedMapOf<String, WifiDirectDnsSdServiceResponse>()
    private var dnsSdCleanupGeneration: Long = 0L
    private var dnsSdLocalServiceCleanupPending: Boolean = false
    private var dnsSdServiceRequestCleanupPending: Boolean = false

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
        requestLocalDeviceInfo(client)
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
                requestLocalDeviceInfo(client)
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

    override fun connectToPeer(
        peer: WifiDirectPeer,
        rolePreference: WifiDirectRolePreference
    ) {
        val permissionStatus = readPermissionStatusSafely()
        safeWifiDirectControllerLogDebug(
            "connectToPeer requested: ${wifiDirectConnectRequestDebugText(peer, rolePreference)}"
        )
        val decision = wifiDirectConnectCommandDecision(
            permissionStatus = permissionStatus,
            platformClientAvailable = platformClient != null,
            currentConnectionStatus = connectionStatus,
            visiblePeers = peers,
            requestedPeer = peer
        )
        when (decision) {
            is WifiDirectConnectCommandDecision.Blocked -> {
                safeWifiDirectControllerLogWarning(
                    "connectToPeer blocked: ${wifiDirectConnectRequestDebugText(peer, rolePreference)} reason=${decision.reason}"
                )
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
        safeWifiDirectControllerLogDebug(
            "connectToPeer accepted: ${wifiDirectConnectRequestDebugText(targetPeer, rolePreference)}"
        )

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
            rolePreference = rolePreference,
            onSuccess = {
                safeWifiDirectControllerLogDebug(
                    "connectToPeer platform success: ${wifiDirectConnectRequestDebugText(targetPeer, rolePreference)}"
                )
                clearConnectionError()
                requestConnectionSnapshot(client)
            },
            onFailure = { reason ->
                safeWifiDirectControllerLogWarning(
                    "connectToPeer platform failure: ${wifiDirectConnectRequestDebugText(targetPeer, rolePreference)} reason=${wifiDirectFailureLabel(reason)}"
                )
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
        safeWifiDirectControllerLogDebug(
            "disconnect requested: state=${connectionStatus.state.name.lowercase()} " +
                "role=${connectionStatus.role.name.lowercase()} " +
                "group=${connectionStatus.groupFormed.name.lowercase()}"
        )
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
                        safeWifiDirectControllerLogDebug(
                            "disconnect cancelConnect success"
                        )
                        connectionStatus = wifiDirectDisconnectedStatus(connectionStatus)
                        emitCurrentRuntimeStatus()
                    },
                    onFailure = { reason ->
                        safeWifiDirectControllerLogWarning(
                            "disconnect cancelConnect failure: ${wifiDirectFailureLabel(reason)}"
                        )
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
                        safeWifiDirectControllerLogDebug(
                            "disconnect removeGroup success"
                        )
                        connectionStatus = wifiDirectDisconnectedStatus(connectionStatus)
                        emitCurrentRuntimeStatus()
                    },
                    onFailure = { reason ->
                        safeWifiDirectControllerLogWarning(
                            "disconnect removeGroup failure: ${wifiDirectFailureLabel(reason)}"
                        )
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

    override fun registerAutomatedDiagnosticsService(
        correlationToken: String,
        deviceNameHint: String?
    ) {
        invalidateDnsSdCleanupTracking()
        val permissionStatus = readPermissionStatusSafely()
        val blockReason = wifiDirectDiscoveryBlockedReason(permissionStatus)
        if (blockReason != null) {
            dnsSdDiagnostics = dnsSdDiagnostics.copy(
                localServiceRegistered = false,
                localServiceInstanceName = null,
                serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                lastError = blockReason,
                cleanupCompleted = false
            )
            emit(permissionStatus)
            return
        }
        val client = platformClient ?: run {
            dnsSdDiagnostics = dnsSdDiagnostics.copy(
                localServiceRegistered = false,
                localServiceInstanceName = null,
                serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                lastError = "Wi-Fi Direct unsupported on this device.",
                cleanupCompleted = false
            )
            emit(permissionStatus)
            return
        }
        configureAutomatedDiagnosticsDnsSdListeners(client)
        client.clearLocalDnsSdServices(
            onSuccess = {
                dnsSdDiagnostics = dnsSdDiagnostics.copy(
                    localServiceRegistered = false,
                    localServiceInstanceName = null,
                    serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                    lastError = null,
                    cleanupCompleted = false
                )
                client.addLocalDnsSdService(
                    serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                    txtRecord = automatedDiagnosticsWifiDirectDnsSdTxtRecord(correlationToken),
                    onSuccess = {
                        dnsSdDiagnostics = dnsSdDiagnostics.copy(
                            localServiceRegistered = true,
                            localServiceInstanceName =
                            automatedDiagnosticsWifiDirectDnsSdInstanceName,
                            serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                            lastError = null,
                            cleanupCompleted = false
                        )
                        clearLastError()
                        touch()
                        emitCurrentRuntimeStatus()
                    },
                    onFailure = { reason ->
                        dnsSdDiagnostics = dnsSdDiagnostics.copy(
                            localServiceRegistered = false,
                            localServiceInstanceName = null,
                            serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                            lastError =
                            "Wi-Fi Direct diagnostics service registration failed: " +
                                wifiDirectFailureLabel(reason),
                            cleanupCompleted = false
                        )
                        touch()
                        emitCurrentRuntimeStatus()
                    }
                )
            },
            onFailure = { reason ->
                dnsSdDiagnostics = dnsSdDiagnostics.copy(
                    localServiceRegistered = false,
                    localServiceInstanceName = null,
                    serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                    lastError =
                    "Wi-Fi Direct diagnostics service cleanup failed: " +
                        wifiDirectFailureLabel(reason),
                    cleanupCompleted = false
                )
                touch()
                emitCurrentRuntimeStatus()
            }
        )
    }

    override fun startAutomatedDiagnosticsServiceDiscovery() {
        invalidateDnsSdCleanupTracking()
        val permissionStatus = readPermissionStatusSafely()
        val blockReason = wifiDirectDiscoveryBlockedReason(permissionStatus)
        if (blockReason != null) {
            dnsSdDiagnostics = dnsSdDiagnostics.copy(
                serviceRequestRegistered = false,
                discoveryStarted = false,
                serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                lastError = blockReason,
                cleanupCompleted = false
            )
            emit(permissionStatus)
            return
        }
        val client = platformClient ?: run {
            dnsSdDiagnostics = dnsSdDiagnostics.copy(
                serviceRequestRegistered = false,
                discoveryStarted = false,
                serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                lastError = "Wi-Fi Direct unsupported on this device.",
                cleanupCompleted = false
            )
            emit(permissionStatus)
            return
        }
        configureAutomatedDiagnosticsDnsSdListeners(client)
        client.clearDnsSdServiceRequests(
            onSuccess = {
                dnsSdDiagnostics = dnsSdDiagnostics.copy(
                    serviceRequestRegistered = false,
                    discoveryStarted = false,
                    serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                    lastError = null,
                    cleanupCompleted = false
                )
                client.addDnsSdServiceRequest(
                    onSuccess = {
                        dnsSdDiagnostics = dnsSdDiagnostics.copy(
                            serviceRequestRegistered = true,
                            serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                            lastError = null,
                            cleanupCompleted = false
                        )
                        client.discoverDnsSdServices(
                            onSuccess = {
                                dnsSdDiagnostics = dnsSdDiagnostics.copy(
                                    discoveryStarted = true,
                                    serviceType =
                                    automatedDiagnosticsWifiDirectDnsSdServiceType,
                                    lastError = null,
                                    cleanupCompleted = false
                                )
                                clearLastError()
                                touch()
                                emitCurrentRuntimeStatus()
                            },
                            onFailure = { reason ->
                                dnsSdDiagnostics = dnsSdDiagnostics.copy(
                                    discoveryStarted = false,
                                    serviceType =
                                    automatedDiagnosticsWifiDirectDnsSdServiceType,
                                    lastError =
                                    "Wi-Fi Direct diagnostics discovery failed: " +
                                        wifiDirectFailureLabel(reason),
                                    cleanupCompleted = false
                                )
                                touch()
                                emitCurrentRuntimeStatus()
                            }
                        )
                    },
                    onFailure = { reason ->
                        dnsSdDiagnostics = dnsSdDiagnostics.copy(
                            serviceRequestRegistered = false,
                            discoveryStarted = false,
                            serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                            lastError =
                            "Wi-Fi Direct diagnostics request failed: " +
                                wifiDirectFailureLabel(reason),
                            cleanupCompleted = false
                        )
                        touch()
                        emitCurrentRuntimeStatus()
                    }
                )
            },
            onFailure = { reason ->
                dnsSdDiagnostics = dnsSdDiagnostics.copy(
                    serviceRequestRegistered = false,
                    discoveryStarted = false,
                    serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                    lastError =
                    "Wi-Fi Direct diagnostics request cleanup failed: " +
                        wifiDirectFailureLabel(reason),
                    cleanupCompleted = false
                )
                touch()
                emitCurrentRuntimeStatus()
            }
        )
    }

    override fun clearAutomatedDiagnosticsServiceDiscovery() {
        clearAutomatedDiagnosticsServiceDiscoveryInternal(platformClient)
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
                    requestLocalDeviceInfo(platformClient)
                    requestPeers(platformClient)
                } else {
                    emitCurrentRuntimeStatus()
                }
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                platformClient?.let { client ->
                    requestLocalDeviceInfo(client)
                    requestPeers(client)
                } ?: emitCurrentRuntimeStatus()
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                when (event.isConnectionEstablished) {
                    true -> platformClient?.let { client ->
                        requestLocalDeviceInfo(client)
                        requestConnectionSnapshot(client)
                    } ?: emitCurrentRuntimeStatus()
                    false -> {
                        connectionStatus = wifiDirectDisconnectedStatus(connectionStatus)
                        clearAutomatedDiagnosticsServiceDiscoveryInternal(platformClient)
                        emitCurrentRuntimeStatus()
                    }
                    null -> platformClient?.let { client ->
                        requestLocalDeviceInfo(client)
                        requestConnectionSnapshot(client)
                    } ?: emitCurrentRuntimeStatus()
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

    private fun requestLocalDeviceInfo(client: WifiDirectPlatformClient) {
        client.requestLocalDeviceInfo(
            onSuccess = { info ->
                localDeviceInfo = info
                touch()
                emitCurrentRuntimeStatus()
            },
            onFailure = { reason ->
                localDeviceInfo = localDeviceInfo.copy(
                    lastError = "Wi-Fi Direct local device info unavailable: $reason"
                )
                touch()
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
            localDeviceInfo = localDeviceInfo,
            dnsSdDiagnostics = dnsSdDiagnostics.copy(
                discoveredServices = dnsSdResponsesByKey.values.toList()
            ),
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
                localDeviceInfo = localDeviceInfo,
                dnsSdDiagnostics = dnsSdDiagnostics.copy(
                    discoveredServices = dnsSdResponsesByKey.values.toList()
                ),
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

    private fun configureAutomatedDiagnosticsDnsSdListeners(
        client: WifiDirectPlatformClient
    ) {
        client.setDnsSdResponseListeners(
            onServiceAvailable = { instanceName, serviceType, peer ->
                recordAutomatedDiagnosticsDnsSdServiceAvailable(
                    instanceName = instanceName,
                    serviceType = serviceType,
                    peer = peer
                )
            },
            onTxtRecordAvailable = { fullDomain, txtRecord, peer ->
                recordAutomatedDiagnosticsDnsSdTxtRecord(
                    fullDomain = fullDomain,
                    txtRecord = txtRecord,
                    peer = peer
                )
            },
            onFailure = { reason ->
                invalidateDnsSdCleanupTracking()
                dnsSdDiagnostics = dnsSdDiagnostics.copy(
                    serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                    lastError = "Wi-Fi Direct DNS-SD listener failed: $reason",
                    cleanupCompleted = false
                )
                touch()
                emitCurrentRuntimeStatus()
            }
        )
    }

    private fun recordAutomatedDiagnosticsDnsSdServiceAvailable(
        instanceName: String?,
        serviceType: String?,
        peer: WifiDirectPeer
    ) {
        updateAutomatedDiagnosticsDnsSdResponse(
            serviceType = serviceType,
            instanceName = instanceName,
            peer = peer,
            txtRecord = null
        )
    }

    private fun recordAutomatedDiagnosticsDnsSdTxtRecord(
        fullDomain: String?,
        txtRecord: Map<String, String>,
        peer: WifiDirectPeer
    ) {
        val serviceType = dnsSdServiceTypeFromFullDomain(fullDomain)
        val instanceName = fullDomain
            ?.substringBefore('.')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        updateAutomatedDiagnosticsDnsSdResponse(
            serviceType = serviceType,
            instanceName = instanceName,
            peer = peer,
            txtRecord = txtRecord
        )
    }

    private fun updateAutomatedDiagnosticsDnsSdResponse(
        serviceType: String?,
        instanceName: String?,
        peer: WifiDirectPeer,
        txtRecord: Map<String, String>?
    ) {
        invalidateDnsSdCleanupTracking()
        val normalizedServiceType = serviceType?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedInstanceName = instanceName?.trim()?.takeIf { it.isNotEmpty() }
        val key = dnsSdResponseKey(
            serviceType = normalizedServiceType,
            instanceName = normalizedInstanceName,
            peer = peer
        )
        val previous = dnsSdResponsesByKey[key]
        dnsSdResponsesByKey[key] = WifiDirectDnsSdServiceResponse(
            serviceType = normalizedServiceType
                ?: previous?.serviceType
                ?: automatedDiagnosticsWifiDirectDnsSdServiceType,
            instanceName = normalizedInstanceName ?: previous?.instanceName,
            peer = peer,
            txtRecord = txtRecord ?: previous?.txtRecord.orEmpty(),
            observedAtMillis = nowMillis()
        )
        dnsSdDiagnostics = dnsSdDiagnostics.copy(
            serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
            discoveredServices = dnsSdResponsesByKey.values.toList(),
            lastError = null,
            cleanupCompleted = false
        )
        touch()
        emitCurrentRuntimeStatus()
    }

    private fun clearAutomatedDiagnosticsServiceDiscoveryInternal(
        client: WifiDirectPlatformClient?
    ) {
        dnsSdResponsesByKey.clear()
        val activeClient = client
        if (activeClient == null) {
            invalidateDnsSdCleanupTracking()
            dnsSdDiagnostics = dnsSdDiagnostics.copy(
                localServiceRegistered = false,
                localServiceInstanceName = null,
                serviceRequestRegistered = false,
                discoveryStarted = false,
                serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                discoveredServices = emptyList(),
                lastError = null,
                cleanupCompleted = true
            )
            touch()
            emitCurrentRuntimeStatus()
            return
        }
        val cleanupGeneration = beginDnsSdCleanupTracking()
        dnsSdDiagnostics = dnsSdDiagnostics.copy(
            localServiceRegistered = false,
            localServiceInstanceName = null,
            serviceRequestRegistered = false,
            discoveryStarted = false,
            serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
            discoveredServices = emptyList(),
            lastError = null,
            cleanupCompleted = false
        )
        touch()
        emitCurrentRuntimeStatus()
        activeClient.clearLocalDnsSdServices(
            onSuccess = {
                resolveDnsSdCleanupOperation(
                    generation = cleanupGeneration,
                    localServicesCompleted = true
                )
            },
            onFailure = { reason ->
                resolveDnsSdCleanupOperation(
                    generation = cleanupGeneration,
                    localServicesCompleted = true,
                    error = "Wi-Fi Direct diagnostics local-service cleanup failed: " +
                        wifiDirectFailureLabel(reason)
                )
            }
        )
        activeClient.clearDnsSdServiceRequests(
            onSuccess = {
                resolveDnsSdCleanupOperation(
                    generation = cleanupGeneration,
                    serviceRequestsCompleted = true
                )
            },
            onFailure = { reason ->
                resolveDnsSdCleanupOperation(
                    generation = cleanupGeneration,
                    serviceRequestsCompleted = true,
                    error = "Wi-Fi Direct diagnostics request cleanup failed: " +
                        wifiDirectFailureLabel(reason)
                )
            }
        )
    }

    private fun invalidateDnsSdCleanupTracking() {
        dnsSdCleanupGeneration += 1L
        dnsSdLocalServiceCleanupPending = false
        dnsSdServiceRequestCleanupPending = false
    }

    private fun beginDnsSdCleanupTracking(): Long {
        dnsSdCleanupGeneration += 1L
        dnsSdLocalServiceCleanupPending = true
        dnsSdServiceRequestCleanupPending = true
        return dnsSdCleanupGeneration
    }

    private fun resolveDnsSdCleanupOperation(
        generation: Long,
        localServicesCompleted: Boolean = false,
        serviceRequestsCompleted: Boolean = false,
        error: String? = null
    ) {
        if (generation != dnsSdCleanupGeneration) {
            return
        }
        if (localServicesCompleted) {
            dnsSdLocalServiceCleanupPending = false
        }
        if (serviceRequestsCompleted) {
            dnsSdServiceRequestCleanupPending = false
        }
        val preservedError = error ?: dnsSdDiagnostics.lastError
        dnsSdDiagnostics = dnsSdDiagnostics.copy(
            lastError = preservedError,
            cleanupCompleted = !dnsSdLocalServiceCleanupPending &&
                !dnsSdServiceRequestCleanupPending &&
                preservedError == null
        )
        touch()
        emitCurrentRuntimeStatus()
    }

    private fun dnsSdServiceTypeFromFullDomain(
        fullDomain: String?
    ): String? {
        val parts = fullDomain
            ?.trim()
            ?.trimEnd('.')
            ?.split('.')
            ?.filter { it.isNotBlank() }
            ?: return null
        if (parts.size < 3) {
            return null
        }
        return "${parts[1]}.${parts[2]}"
    }

    private fun dnsSdResponseKey(
        serviceType: String?,
        instanceName: String?,
        peer: WifiDirectPeer
    ): String {
        return listOf(
            serviceType?.trim().orEmpty(),
            instanceName?.trim().orEmpty(),
            normalizeWifiDirectDeviceAddress(peer.deviceAddress)
                ?: peer.deviceAddress?.trim().orEmpty(),
            peer.deviceName?.trim().orEmpty()
        ).joinToString(separator = "|")
    }
}

private fun safeWifiDirectControllerLogDebug(
    message: String
) {
    runCatching {
        Log.d(androidWifiDirectControllerLogTag, message)
    }
}

private fun safeWifiDirectControllerLogWarning(
    message: String
) {
    runCatching {
        Log.w(androidWifiDirectControllerLogTag, message)
    }
}
