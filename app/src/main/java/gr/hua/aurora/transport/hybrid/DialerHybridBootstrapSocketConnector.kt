package gr.hua.aurora.transport.hybrid

class DialerHybridBootstrapSocketConnector(
    private val dialer: HybridBootstrapSocketDialer
) : HybridBootstrapSocketConnector {
    override fun connect(
        plan: HybridBootstrapSocketExecutionPlan
    ): HybridBootstrapSocketConnectionResult {
        return when (
            val result = dialer.dial(
                address = plan.groupOwnerAddress,
                port = plan.socketPort,
                connectTimeoutMillis = plan.connectTimeoutMillis
            )
        ) {
            is HybridBootstrapSocketDialResult.Connected ->
                HybridBootstrapSocketConnectionResult.Connected(
                    peerId = plan.peerId,
                    sessionId = plan.sessionId,
                    bootstrapIdentifier = plan.bootstrapIdentifier,
                    groupOwnerAddress = result.address,
                    socketPort = result.port,
                    connectedAtMillis = result.connectedAtMillis
                )

            is HybridBootstrapSocketDialResult.Failed ->
                HybridBootstrapSocketConnectionResult.Failed(result.reason)
        }
    }
}
