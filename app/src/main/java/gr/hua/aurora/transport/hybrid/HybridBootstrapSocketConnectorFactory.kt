package gr.hua.aurora.transport.hybrid

object HybridBootstrapSocketConnectorFactory {
    fun disabled(
        failureReason: String = "Hybrid bootstrap socket connector is disabled."
    ): HybridBootstrapSocketConnector {
        return DisabledHybridBootstrapSocketConnector(
            failureReason = failureReason
        )
    }

    fun dialerBacked(
        dialer: HybridBootstrapSocketDialer
    ): HybridBootstrapSocketConnector {
        return DialerHybridBootstrapSocketConnector(
            dialer = dialer
        )
    }

    fun javaNet(): HybridBootstrapSocketConnector {
        return dialerBacked(
            JavaNetHybridBootstrapSocketDialer()
        )
    }

    fun defaultRuntimeConnector(): HybridBootstrapSocketConnector {
        return disabled()
    }
}
