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
                canCloseSocket = false
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
                connectHost = "192.168.49.1"
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
