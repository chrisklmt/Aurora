package gr.hua.aurora.wifidirect

import android.content.Context
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper

private const val wifiDirectStatusUnavailableMessage = "Wi-Fi Direct status unavailable"

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
            WifiDirectPermissionStatus(
                requiredPermissions = WifiDirectPermissionStatusReader.requiredPermissionsForSdkInt(sdkInt),
                missingPermissions = emptySet(),
                isWifiDirectSupported = false,
                isWifiEnabled = null
            )
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
                peers = discoveredPeers
                    .distinctBy { peer -> peer.deviceAddress?.uppercase() ?: peer.deviceName.orEmpty() }
                    .sortedWith(
                        compareBy(
                            WifiDirectPeer::deviceName,
                            WifiDirectPeer::deviceAddress
                        )
                    )
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

        return WifiDirectRuntimeStatus(
            permissionStatus = permissionStatus,
            discoveryState = discoveryState,
            transportState = WifiDirectTransportState.NOT_WIRED,
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
        val status = WifiDirectRuntimeStatus(
            permissionStatus = permissionStatus,
            discoveryState = discoveryState,
            transportState = WifiDirectTransportState.NOT_WIRED,
            peers = peers,
            lastError = lastError,
            lastUpdatedAtMillis = lastUpdatedAtMillis
        )
        listeners.forEach { listener ->
            listener.onRuntimeStatusChanged(status)
        }
    }

    private fun emit(status: WifiDirectRuntimeStatus) {
        listeners.forEach { listener ->
            listener.onRuntimeStatusChanged(status)
        }
    }

    private fun readPermissionStatusSafely(): WifiDirectPermissionStatus {
        val baseStatus = runCatching(permissionStatusReader).getOrElse { error ->
            setLastError("$wifiDirectStatusUnavailableMessage: ${error::class.java.simpleName}")
            fallbackPermissionStatus()
        }
        return if (latestWifiP2pEnabled != null) {
            baseStatus.copy(isWifiP2pEnabled = latestWifiP2pEnabled)
        } else {
            baseStatus
        }
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
}

private class AndroidWifiDirectPlatformClient private constructor(
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
                onSuccess(
                    peerList.deviceList.map { device ->
                        WifiDirectPeer(
                            deviceName = device.deviceName?.trim()?.takeIf { it.isNotEmpty() },
                            deviceAddress = device.deviceAddress?.trim()?.takeIf { it.isNotEmpty() }
                        )
                    }
                )
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

internal fun wifiDirectDiscoveryBlockedReason(
    permissionStatus: WifiDirectPermissionStatus
): String? {
    if (!permissionStatus.isWifiDirectSupported) {
        return "Wi-Fi Direct unsupported on this device."
    }
    if (permissionStatus.hasMissingNearbyWifiPermission) {
        return "Missing Nearby Wi-Fi permission."
    }
    if (permissionStatus.hasMissingLocationPermission) {
        return "Missing location permission."
    }
    if (!permissionStatus.allRequiredGranted) {
        return "Missing Wi-Fi Direct permission."
    }
    return when (permissionStatus.enabledState) {
        WifiDirectEnabledState.ENABLED -> null
        WifiDirectEnabledState.DISABLED -> "Wi-Fi Direct is disabled."
        WifiDirectEnabledState.UNKNOWN -> "Wi-Fi Direct state unavailable."
    }
}

internal fun wifiDirectFailureLabel(
    reason: Int
): String {
    return when (reason) {
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
        WifiP2pManager.ERROR -> "error"
        else -> "code $reason"
    }
}
