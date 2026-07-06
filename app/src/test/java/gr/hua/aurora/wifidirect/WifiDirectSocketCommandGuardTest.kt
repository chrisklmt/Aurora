package gr.hua.aurora.wifidirect

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiDirectSocketCommandGuardTest {
    @Test
    fun groupOwnerRoleEnablesServerAction() {
        assertEquals(
            WifiDirectSocketCommandAvailability(
                canStartServer = true,
                canConnectClient = false,
                canSendFrame = false,
                canCloseSocket = false,
                connectClientBlockedReason = "Connect client only on Wi-Fi Direct client."
            ),
            wifiDirectSocketCommandAvailability(
                runtimeStatus = runtimeStatus(
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.CONNECTED,
                        groupFormed = WifiDirectGroupFormedState.YES,
                        role = WifiDirectConnectionRole.GROUP_OWNER
                    )
                ),
                diagnostics = WifiDirectSocketDiagnostics()
            )
        )
    }

    @Test
    fun clientRoleWithOwnerAddressEnablesClientAction() {
        assertEquals(
            WifiDirectSocketCommandAvailability(
                canStartServer = false,
                canConnectClient = true,
                canSendFrame = false,
                canCloseSocket = false,
                connectHost = "192.168.49.1",
                startServerBlockedReason = "Start server only on group owner."
            ),
            wifiDirectSocketCommandAvailability(
                runtimeStatus = runtimeStatus(
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.CONNECTED,
                        groupFormed = WifiDirectGroupFormedState.YES,
                        role = WifiDirectConnectionRole.CLIENT,
                        groupOwnerAddress = "192.168.49.1"
                    )
                ),
                diagnostics = WifiDirectSocketDiagnostics()
            )
        )
    }

    @Test
    fun unknownGroupStateDisablesActionsClearly() {
        assertEquals(
            WifiDirectSocketCommandAvailability(
                canStartServer = false,
                canConnectClient = false,
                canSendFrame = false,
                canCloseSocket = false,
                startServerBlockedReason = "Wi-Fi Direct group not formed.",
                connectClientBlockedReason = "Wi-Fi Direct group not formed.",
                helpText = "Wi-Fi Direct group not formed."
            ),
            wifiDirectSocketCommandAvailability(
                runtimeStatus = runtimeStatus(),
                diagnostics = WifiDirectSocketDiagnostics()
            )
        )
    }

    @Test
    fun connectedStateEnablesFrameSendAndClose() {
        assertEquals(
            WifiDirectSocketCommandAvailability(
                canStartServer = false,
                canConnectClient = false,
                canSendFrame = true,
                canCloseSocket = true
            ),
            wifiDirectSocketCommandAvailability(
                runtimeStatus = runtimeStatus(
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.CONNECTED,
                        groupFormed = WifiDirectGroupFormedState.YES,
                        role = WifiDirectConnectionRole.CLIENT,
                        groupOwnerAddress = "192.168.49.1"
                    )
                ),
                diagnostics = WifiDirectSocketDiagnostics(
                    state = WifiDirectSocketState.CONNECTED,
                    role = WifiDirectSocketRole.CLIENT,
                    isConnected = true
                )
            )
        )
    }

    @Test
    fun macLikeOwnerAddressDoesNotEnableSocketClientConnect() {
        assertEquals(
            WifiDirectSocketCommandAvailability(
                canStartServer = false,
                canConnectClient = false,
                canSendFrame = false,
                canCloseSocket = false,
                connectHost = null,
                startServerBlockedReason = "Start server only on group owner.",
                connectClientBlockedReason = "Socket client needs the group owner IP address.",
                helpText = "Socket client needs the group owner IP address."
            ),
            wifiDirectSocketCommandAvailability(
                runtimeStatus = runtimeStatus(
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.CONNECTED,
                        groupFormed = WifiDirectGroupFormedState.YES,
                        role = WifiDirectConnectionRole.CLIENT,
                        groupOwnerAddress = "AA:BB:CC:DD:EE:01"
                    )
                ),
                diagnostics = WifiDirectSocketDiagnostics()
            )
        )
    }

    @Test
    fun guardSummaryIncludesRoleGroupAndResolvedHost() {
        val runtimeStatus = runtimeStatus(
            connectionStatus = WifiDirectConnectionStatus(
                state = WifiDirectConnectionState.CONNECTED,
                groupFormed = WifiDirectGroupFormedState.YES,
                role = WifiDirectConnectionRole.CLIENT,
                groupOwnerAddress = "192.168.49.1"
            )
        )
        val availability = wifiDirectSocketCommandAvailability(
            runtimeStatus = runtimeStatus,
            diagnostics = WifiDirectSocketDiagnostics()
        )

        assertEquals(
            "command=connectClient role=client group=yes host=192.168.49.1 accepted=true blocked=none",
            wifiDirectSocketCommandGuardSummary(
                wifiDirectSocketCommandGuardSnapshot(
                    command = WifiDirectSocketCommand.CONNECT_CLIENT,
                    runtimeStatus = runtimeStatus,
                    availability = availability
                )
            )
        )
    }

    private fun runtimeStatus(
        connectionStatus: WifiDirectConnectionStatus = WifiDirectConnectionStatus()
    ): WifiDirectRuntimeStatus {
        return WifiDirectRuntimeStatus(
            permissionStatus = WifiDirectPermissionStatus(
                requiredPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                missingPermissions = emptySet(),
                isWifiDirectSupported = true,
                isWifiEnabled = true,
                isWifiP2pEnabled = true
            ),
            connectionStatus = connectionStatus
        )
    }
}
