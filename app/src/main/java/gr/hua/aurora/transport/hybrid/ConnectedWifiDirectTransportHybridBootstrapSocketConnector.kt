package gr.hua.aurora.transport.hybrid

import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterState
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketDiagnostics

internal class ConnectedWifiDirectTransportHybridBootstrapSocketConnector(
    private val currentSocketDiagnostics: () -> WifiDirectSocketDiagnostics,
    private val currentAdapterDiagnostics: () -> WifiDirectTransportAdapterDiagnostics,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : HybridBootstrapSocketConnector {
    override fun connect(
        plan: HybridBootstrapSocketExecutionPlan
    ): HybridBootstrapSocketConnectionResult {
        val socketDiagnostics = currentSocketDiagnostics()
        if (!socketDiagnostics.isConnected) {
            return HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap existing Wi-Fi Direct socket is not connected."
            )
        }
        if (!socketDiagnostics.isReadLoopActive) {
            return HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap existing Wi-Fi Direct read loop is not active."
            )
        }

        val adapterDiagnostics = currentAdapterDiagnostics()
        if (adapterDiagnostics.state != WifiDirectTransportAdapterState.READY) {
            val detail = adapterDiagnostics.notReadyReason
                ?: adapterDiagnostics.lastError
                ?: "Wi-Fi Direct transport adapter is not ready."
            return HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap existing Wi-Fi Direct transport adapter is not ready: $detail"
            )
        }

        val connectedEndpoint = socketDiagnostics.endpoint
            ?: return HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap existing Wi-Fi Direct endpoint is unavailable."
            )
        val connectedAddress = connectedEndpoint.host
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap existing Wi-Fi Direct endpoint address is unavailable."
            )
        val connectedPort = connectedEndpoint.port
            ?: return HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap existing Wi-Fi Direct endpoint port is unavailable."
            )

        if (connectedAddress != plan.groupOwnerAddress) {
            return HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap existing Wi-Fi Direct endpoint mismatch: connected address $connectedAddress does not match requested ${plan.groupOwnerAddress}."
            )
        }
        if (connectedPort != plan.socketPort) {
            return HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap existing Wi-Fi Direct endpoint mismatch: connected port $connectedPort does not match requested ${plan.socketPort}."
            )
        }

        return HybridBootstrapSocketConnectionResult.Connected(
            peerId = plan.peerId,
            sessionId = plan.sessionId,
            bootstrapIdentifier = plan.bootstrapIdentifier,
            groupOwnerAddress = connectedAddress,
            socketPort = connectedPort,
            connectedAtMillis = nowMillis()
        )
    }
}
