package gr.hua.aurora.diagnostics.automated

data class AutomatedDiagnosticsTimingWindow(
    val stabilizationMillis: Long,
    val timeoutMillis: Long,
    val maxRetries: Int = 0
) {
    init {
        require(stabilizationMillis >= 0L) {
            "Automated diagnostics stabilizationMillis must be non-negative."
        }
        require(timeoutMillis > 0L) {
            "Automated diagnostics timeoutMillis must be positive."
        }
        require(timeoutMillis >= stabilizationMillis) {
            "Automated diagnostics timeoutMillis must be at least stabilizationMillis."
        }
        require(maxRetries >= 0) {
            "Automated diagnostics maxRetries must be non-negative."
        }
    }
}

data class AutomatedDiagnosticsTimingPolicy(
    val recompositionSettle: AutomatedDiagnosticsTimingWindow,
    val bleRuntimeStable: AutomatedDiagnosticsTimingWindow,
    val auroraPeerDiscovery: AutomatedDiagnosticsTimingWindow,
    val bleConnection: AutomatedDiagnosticsTimingWindow,
    val securePeerSelection: AutomatedDiagnosticsTimingWindow,
    val identityExchange: AutomatedDiagnosticsTimingWindow,
    val secureSessionReadiness: AutomatedDiagnosticsTimingWindow,
    val sharedRunCoordination: AutomatedDiagnosticsTimingWindow,
    val bleFinalStability: AutomatedDiagnosticsTimingWindow,
    val phaseBarrierSync: AutomatedDiagnosticsTimingWindow,
    val wifiDirectDiscovery: AutomatedDiagnosticsTimingWindow,
    val wifiDirectGroupFormation: AutomatedDiagnosticsTimingWindow,
    val wifiDirectSocketConnection: AutomatedDiagnosticsTimingWindow,
    val wifiDirectAdapterReadiness: AutomatedDiagnosticsTimingWindow,
    val wifiDirectBridgeEnable: AutomatedDiagnosticsTimingWindow,
    val hybridControlDelivery: AutomatedDiagnosticsTimingWindow,
    val hybridSocketHintDelivery: AutomatedDiagnosticsTimingWindow,
    val hybridBootstrapTrigger: AutomatedDiagnosticsTimingWindow,
    val applicationProbeDelivery: AutomatedDiagnosticsTimingWindow,
    val finalValidation: AutomatedDiagnosticsTimingWindow,
    val applicationProbeDuplicateObservationMillis: Long,
    val sharedRunAnnouncementLeaseMillis: Long,
    val sharedRunActiveLeaseMillis: Long,
    val sharedRunConflictWindowMillis: Long,
    val crossDeviceClockSkewToleranceMillis: Long,
    val automatedDiagnosticsServerReadyExpiryMillis: Long,
    val automatedDiagnosticsPhaseStateLeaseMillis: Long,
    val automatedDiagnosticsPhaseStateRefreshMillis: Long = 4_000L,
    val automatedDiagnosticsWifiDirectPeerReadyRefreshMillis: Long = 4_000L,
    val pollIntervalMillis: Long = 100L
) {
    init {
        require(sharedRunAnnouncementLeaseMillis > 0L) {
            "Automated diagnostics sharedRunAnnouncementLeaseMillis must be positive."
        }
        require(applicationProbeDuplicateObservationMillis > 0L) {
            "Automated diagnostics applicationProbeDuplicateObservationMillis must be positive."
        }
        require(sharedRunActiveLeaseMillis > 0L) {
            "Automated diagnostics sharedRunActiveLeaseMillis must be positive."
        }
        require(sharedRunConflictWindowMillis >= 0L) {
            "Automated diagnostics sharedRunConflictWindowMillis must be non-negative."
        }
        require(crossDeviceClockSkewToleranceMillis >= 0L) {
            "Automated diagnostics crossDeviceClockSkewToleranceMillis must be non-negative."
        }
        require(automatedDiagnosticsServerReadyExpiryMillis > 0L) {
            "Automated diagnostics automatedDiagnosticsServerReadyExpiryMillis must be positive."
        }
        require(automatedDiagnosticsPhaseStateLeaseMillis > 0L) {
            "Automated diagnostics automatedDiagnosticsPhaseStateLeaseMillis must be positive."
        }
        require(automatedDiagnosticsPhaseStateRefreshMillis > 0L) {
            "Automated diagnostics automatedDiagnosticsPhaseStateRefreshMillis must be positive."
        }
        require(automatedDiagnosticsWifiDirectPeerReadyRefreshMillis > 0L) {
            "Automated diagnostics automatedDiagnosticsWifiDirectPeerReadyRefreshMillis must be positive."
        }
        require(pollIntervalMillis > 0L) {
            "Automated diagnostics pollIntervalMillis must be positive."
        }
    }

    companion object {
        fun default(): AutomatedDiagnosticsTimingPolicy {
            return AutomatedDiagnosticsTimingPolicy(
                recompositionSettle = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 300L,
                    timeoutMillis = 2_000L
                ),
                bleRuntimeStable = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 600L,
                    timeoutMillis = 5_000L
                ),
                auroraPeerDiscovery = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 0L,
                    timeoutMillis = 10_000L
                ),
                bleConnection = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 800L,
                    timeoutMillis = 8_000L,
                    maxRetries = 2
                ),
                securePeerSelection = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 300L,
                    timeoutMillis = 3_000L
                ),
                identityExchange = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 700L,
                    timeoutMillis = 8_000L
                ),
                secureSessionReadiness = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 600L,
                    timeoutMillis = 6_000L
                ),
                sharedRunCoordination = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 300L,
                    timeoutMillis = 8_000L
                ),
                bleFinalStability = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 1_000L,
                    timeoutMillis = 5_000L
                ),
                phaseBarrierSync = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 300L,
                    timeoutMillis = 60_000L
                ),
                wifiDirectDiscovery = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 0L,
                    timeoutMillis = 60_000L
                ),
                wifiDirectGroupFormation = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 1_000L,
                    timeoutMillis = 60_000L,
                    maxRetries = 1
                ),
                wifiDirectSocketConnection = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 1_000L,
                    timeoutMillis = 60_000L,
                    maxRetries = 2
                ),
                wifiDirectAdapterReadiness = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 750L,
                    timeoutMillis = 15_000L
                ),
                wifiDirectBridgeEnable = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 300L,
                    timeoutMillis = 10_000L
                ),
                hybridControlDelivery = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 300L,
                    timeoutMillis = 45_000L
                ),
                hybridSocketHintDelivery = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 500L,
                    timeoutMillis = 45_000L
                ),
                hybridBootstrapTrigger = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 300L,
                    timeoutMillis = 60_000L
                ),
                applicationProbeDelivery = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 0L,
                    timeoutMillis = 6_000L
                ),
                finalValidation = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 500L,
                    timeoutMillis = 5_000L
                ),
                applicationProbeDuplicateObservationMillis = 1_250L,
                sharedRunAnnouncementLeaseMillis = 12_000L,
                sharedRunActiveLeaseMillis = 60_000L,
                sharedRunConflictWindowMillis = 3_000L,
                crossDeviceClockSkewToleranceMillis = 2_000L,
                automatedDiagnosticsServerReadyExpiryMillis = 8_000L,
                automatedDiagnosticsPhaseStateLeaseMillis = 15_000L
            )
        }
    }
}
