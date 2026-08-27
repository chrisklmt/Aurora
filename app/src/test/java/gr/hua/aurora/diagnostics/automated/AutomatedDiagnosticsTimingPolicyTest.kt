package gr.hua.aurora.diagnostics.automated

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomatedDiagnosticsTimingPolicyTest {
    @Test
    fun defaultTimingPolicyMatchesPhaseTwoDefaults() {
        val policy = AutomatedDiagnosticsTimingPolicy.default()

        assertEquals(300L, policy.recompositionSettle.stabilizationMillis)
        assertEquals(2_000L, policy.recompositionSettle.timeoutMillis)
        assertEquals(600L, policy.bleRuntimeStable.stabilizationMillis)
        assertEquals(5_000L, policy.bleRuntimeStable.timeoutMillis)
        assertEquals(10_000L, policy.auroraPeerDiscovery.timeoutMillis)
        assertEquals(800L, policy.bleConnection.stabilizationMillis)
        assertEquals(8_000L, policy.bleConnection.timeoutMillis)
        assertEquals(2, policy.bleConnection.maxRetries)
        assertEquals(300L, policy.securePeerSelection.stabilizationMillis)
        assertEquals(3_000L, policy.securePeerSelection.timeoutMillis)
        assertEquals(700L, policy.identityExchange.stabilizationMillis)
        assertEquals(8_000L, policy.identityExchange.timeoutMillis)
        assertEquals(600L, policy.secureSessionReadiness.stabilizationMillis)
        assertEquals(6_000L, policy.secureSessionReadiness.timeoutMillis)
        assertEquals(1_000L, policy.bleFinalStability.stabilizationMillis)
        assertEquals(5_000L, policy.bleFinalStability.timeoutMillis)
        assertEquals(300L, policy.phaseBarrierSync.stabilizationMillis)
        assertEquals(60_000L, policy.phaseBarrierSync.timeoutMillis)
        assertEquals(0L, policy.wifiDirectDiscovery.stabilizationMillis)
        assertEquals(60_000L, policy.wifiDirectDiscovery.timeoutMillis)
        assertEquals(1_000L, policy.wifiDirectGroupFormation.stabilizationMillis)
        assertEquals(60_000L, policy.wifiDirectGroupFormation.timeoutMillis)
        assertEquals(1, policy.wifiDirectGroupFormation.maxRetries)
        assertEquals(1_000L, policy.wifiDirectSocketConnection.stabilizationMillis)
        assertEquals(60_000L, policy.wifiDirectSocketConnection.timeoutMillis)
        assertEquals(2, policy.wifiDirectSocketConnection.maxRetries)
        assertEquals(750L, policy.wifiDirectAdapterReadiness.stabilizationMillis)
        assertEquals(15_000L, policy.wifiDirectAdapterReadiness.timeoutMillis)
        assertEquals(300L, policy.wifiDirectBridgeEnable.stabilizationMillis)
        assertEquals(10_000L, policy.wifiDirectBridgeEnable.timeoutMillis)
        assertEquals(300L, policy.hybridControlDelivery.stabilizationMillis)
        assertEquals(45_000L, policy.hybridControlDelivery.timeoutMillis)
        assertEquals(500L, policy.hybridSocketHintDelivery.stabilizationMillis)
        assertEquals(45_000L, policy.hybridSocketHintDelivery.timeoutMillis)
        assertEquals(300L, policy.hybridBootstrapTrigger.stabilizationMillis)
        assertEquals(60_000L, policy.hybridBootstrapTrigger.timeoutMillis)
        assertEquals(15_000L, policy.automatedDiagnosticsPhaseStateLeaseMillis)
        assertEquals(4_000L, policy.automatedDiagnosticsPhaseStateRefreshMillis)
        assertEquals(4_000L, policy.automatedDiagnosticsWifiDirectPeerReadyRefreshMillis)
        assertEquals(100L, policy.pollIntervalMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun timingWindowRejectsTimeoutShorterThanStabilization() {
        AutomatedDiagnosticsTimingWindow(
            stabilizationMillis = 500L,
            timeoutMillis = 200L
        )
    }
}
