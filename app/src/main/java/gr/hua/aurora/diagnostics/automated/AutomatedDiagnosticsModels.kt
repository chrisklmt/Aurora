package gr.hua.aurora.diagnostics.automated

import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.wifidirect.controller.WifiDirectEnabledState

enum class AutomatedDiagnosticStepStatus {
    WAITING,
    RUNNING,
    PASS,
    FAIL,
    BLOCKED,
    SKIPPED,
    CANCELLED
}

enum class AutomatedDiagnosticsOverallStatus {
    IDLE,
    RUNNING,
    PASS,
    FAIL,
    BLOCKED,
    CANCELLED
}

enum class AutomatedDiagnosticsPeerRole {
    COORDINATOR,
    PARTICIPANT
}

enum class AutomatedDiagnosticsRequiredActionKind {
    REQUEST_BLUETOOTH_PERMISSIONS,
    REQUEST_WIFI_DIRECT_PERMISSIONS,
    OPEN_BLUETOOTH_SETTINGS,
    OPEN_LOCATION_SETTINGS,
    OPEN_WIFI_SETTINGS
}

data class AutomatedDiagnosticsRequiredAction(
    val kind: AutomatedDiagnosticsRequiredActionKind,
    val title: String,
    val buttonLabel: String
) {
    init {
        require(title.isNotBlank()) {
            "Automated diagnostics required action title must not be blank."
        }
        require(buttonLabel.isNotBlank()) {
            "Automated diagnostics required action buttonLabel must not be blank."
        }
    }
}

enum class AutomatedDiagnosticsPreparationItemStatus {
    READY,
    WAITING
}

data class AutomatedDiagnosticsPreparationItem(
    val label: String,
    val status: AutomatedDiagnosticsPreparationItemStatus,
    val detail: String
) {
    init {
        require(label.isNotBlank()) {
            "Automated diagnostics preparation label must not be blank."
        }
        require(detail.isNotBlank()) {
            "Automated diagnostics preparation detail must not be blank."
        }
    }
}

data class AutomatedDiagnosticsPreparationState(
    val isReady: Boolean,
    val summary: String,
    val requiredAction: AutomatedDiagnosticsRequiredAction? = null,
    val items: List<AutomatedDiagnosticsPreparationItem> = emptyList()
) {
    init {
        require(summary.isNotBlank()) {
            "Automated diagnostics preparation summary must not be blank."
        }
    }
}

internal enum class AutomatedDiagnosticsPreparationCommand {
    NONE,
    START_RUN,
    REQUEST_BLUETOOTH_PERMISSIONS,
    REQUEST_WIFI_DIRECT_PERMISSIONS
}

enum class AutomatedDiagnosticStepId(
    val stepNumber: Int,
    val title: String
) {
    PREFLIGHT(1, "Preflight"),
    BLE_RUNTIME(2, "BLE advertiser and scanner"),
    AURORA_PEER_DISCOVERY(3, "Aurora peer discovery"),
    ROLE_ELECTION(4, "Local run role"),
    BLE_CONNECTION(5, "BLE connection"),
    SECURE_PEER_SELECTION(6, "Secure peer selection"),
    IDENTITY_KEY_SETUP(7, "Identity/key setup"),
    SECURE_SESSION_READINESS(8, "Secure session readiness"),
    REMOTE_PARTICIPANT_COORDINATION(9, "Remote participant coordination"),
    BLE_STABILITY(10, "BLE stability"),
    WIFI_DIRECT_DISCOVERY_AND_GROUP(11, "Wi-Fi Direct discovery and group"),
    WIFI_DIRECT_SOCKET(12, "Wi-Fi Direct socket"),
    WIFI_DIRECT_BRIDGES(13, "Wi-Fi Direct bridges"),
    HYBRID_BOOTSTRAP_OFFER(14, "Hybrid bootstrap offer"),
    HYBRID_BOOTSTRAP_ACCEPT(15, "Hybrid bootstrap accept"),
    HYBRID_BOOTSTRAP_SOCKET_HINT(16, "Hybrid bootstrap socket hint"),
    HYBRID_BOOTSTRAP_TRIGGER(17, "Hybrid bootstrap trigger"),
    GLOBAL_MESSAGE_PROBE(18, "Global message probe"),
    PRIVATE_ENCRYPTED_MESSAGE_PROBE(19, "Private encrypted message probe"),
    REVERSE_DIRECTION_MESSAGING_PROBE(20, "Reverse-direction messaging probe"),
    FINAL_END_TO_END_VALIDATION(21, "Final end-to-end validation")
}

data class AutomatedDiagnosticEvidenceValue(
    val label: String,
    val value: String
) {
    init {
        require(label.isNotBlank()) {
            "Automated diagnostic evidence label must not be blank."
        }
        require(value.isNotBlank()) {
            "Automated diagnostic evidence value must not be blank."
        }
    }
}

data class AutomatedDiagnosticStepResult(
    val stepId: AutomatedDiagnosticStepId,
    val visibleStepNumber: Int = stepId.stepNumber,
    val title: String = stepId.title,
    val status: AutomatedDiagnosticStepStatus = AutomatedDiagnosticStepStatus.WAITING,
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val elapsedMillis: Long = 0L,
    val retryCount: Int = 0,
    val summary: String = "Waiting",
    val blockerOrFailure: String? = null,
    val requiredAction: AutomatedDiagnosticsRequiredAction? = null,
    val evidenceValues: List<AutomatedDiagnosticEvidenceValue> = emptyList(),
    val waitingProgressText: String? = null,
    val stabilizationProgressText: String? = null,
    val technicalDetails: List<String> = emptyList()
) {
    init {
        require(visibleStepNumber > 0) {
            "Automated diagnostic step number must be positive."
        }
        require(title.isNotBlank()) {
            "Automated diagnostic step title must not be blank."
        }
        require(elapsedMillis >= 0L) {
            "Automated diagnostic step elapsedMillis must be non-negative."
        }
        require(retryCount >= 0) {
            "Automated diagnostic step retryCount must be non-negative."
        }
        require(summary.isNotBlank()) {
            "Automated diagnostic step summary must not be blank."
        }
    }

    constructor(
        stepId: AutomatedDiagnosticStepId,
        visibleStepNumber: Int = stepId.stepNumber,
        title: String = stepId.title,
        status: AutomatedDiagnosticStepStatus = AutomatedDiagnosticStepStatus.WAITING,
        startedAtMillis: Long? = null,
        completedAtMillis: Long? = null,
        elapsedMillis: Long = 0L,
        retryCount: Int = 0,
        summary: String = "Waiting",
        blockerOrFailure: String? = null,
        evidenceValues: List<AutomatedDiagnosticEvidenceValue> = emptyList(),
        waitingProgressText: String? = null,
        stabilizationProgressText: String? = null,
        technicalDetails: List<String> = emptyList()
    ) : this(
        stepId = stepId,
        visibleStepNumber = visibleStepNumber,
        title = title,
        status = status,
        startedAtMillis = startedAtMillis,
        completedAtMillis = completedAtMillis,
        elapsedMillis = elapsedMillis,
        retryCount = retryCount,
        summary = summary,
        blockerOrFailure = blockerOrFailure,
        requiredAction = null,
        evidenceValues = evidenceValues,
        waitingProgressText = waitingProgressText,
        stabilizationProgressText = stabilizationProgressText,
        technicalDetails = technicalDetails
    )
}

enum class AutomatedDiagnosticsPhase(
    val title: String,
    val reportTitle: String,
    val stepRange: IntRange
) {
    PHASE_1("Phase 1", "Phase 1 Report", 1..10),
    PHASE_2("Phase 2", "Phase 2 Report", 11..17),
    PHASE_3("Phase 3", "Phase 3 Report", 18..21)
}

data class AutomatedDiagnosticsPhaseSection(
    val phase: AutomatedDiagnosticsPhase,
    val steps: List<AutomatedDiagnosticStepResult>,
    val aggregatedStatus: AutomatedDiagnosticStepStatus,
    val reportText: String
) {
    init {
        require(steps.isNotEmpty()) {
            "Automated diagnostics phase section must contain at least one step."
        }
    }
}

data class AutomatedDiagnosticsRunState(
    val overallStatus: AutomatedDiagnosticsOverallStatus,
    val currentStepNumber: Int?,
    val totalSteps: Int,
    val localRunnerExecutionId: String? = null,
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val elapsedMillis: Long = 0L,
    val passedCount: Int = 0,
    val failedCount: Int = 0,
    val blockedCount: Int = 0,
    val cancelledCount: Int = 0,
    val selectedPeerId: String? = null,
    val localPeerRole: AutomatedDiagnosticsPeerRole? = null,
    val sharedRunId: String? = null,
    val sharedRunCoordinatorPeerId: String? = null,
    val sharedRunParticipantPeerId: String? = null,
    val sharedRunSessionAssociationId: String? = null,
    val sharedRunCreatedAtMillis: Long? = null,
    val sharedRunExpiresAtMillis: Long? = null,
    val sharedRunCanonicalPeerPair: String? = null,
    val steps: List<AutomatedDiagnosticStepResult>,
    val phaseTwoSummary: String = "",
    val reportText: String = ""
) {
    init {
        require(totalSteps > 0) {
            "Automated diagnostics totalSteps must be positive."
        }
        require(elapsedMillis >= 0L) {
            "Automated diagnostics elapsedMillis must be non-negative."
        }
        require(passedCount >= 0 && failedCount >= 0 && blockedCount >= 0 && cancelledCount >= 0) {
            "Automated diagnostics aggregate counts must be non-negative."
        }
        require(steps.size == totalSteps) {
            "Automated diagnostics steps size must match totalSteps."
        }
    }

    companion object {
        fun initial(): AutomatedDiagnosticsRunState {
            val steps = AutomatedDiagnosticStepId.entries.map(::AutomatedDiagnosticStepResult)
            return AutomatedDiagnosticsRunState(
                overallStatus = AutomatedDiagnosticsOverallStatus.IDLE,
                currentStepNumber = null,
                totalSteps = steps.size,
                steps = steps,
                reportText = automatedDiagnosticsPlainTextReport(
                    overallStatus = AutomatedDiagnosticsOverallStatus.IDLE,
                    selectedPeerId = null,
                    localPeerRole = null,
                    localRunnerExecutionId = null,
                    sharedRunId = null,
                    sharedRunCoordinatorPeerId = null,
                    sharedRunParticipantPeerId = null,
                    sharedRunSessionAssociationId = null,
                    sharedRunCreatedAtMillis = null,
                    sharedRunExpiresAtMillis = null,
                    sharedRunCanonicalPeerPair = null,
                    elapsedMillis = 0L,
                    steps = steps,
                    phaseTwoSummary = ""
                )
            )
        }
    }
}

fun automatedDiagnosticsCompactSummaryText(
    state: AutomatedDiagnosticsRunState
): String {
    return when (state.overallStatus) {
        AutomatedDiagnosticsOverallStatus.IDLE -> "Idle"
        AutomatedDiagnosticsOverallStatus.RUNNING ->
            "Running step ${state.currentStepNumber ?: "?"}/${state.totalSteps}"
        AutomatedDiagnosticsOverallStatus.PASS ->
            "Pass (${state.passedCount}/${state.totalSteps})"
        AutomatedDiagnosticsOverallStatus.FAIL ->
            "Fail (${state.failedCount} failed)"
        AutomatedDiagnosticsOverallStatus.BLOCKED ->
            "Blocked (${state.blockedCount} blocked)"
        AutomatedDiagnosticsOverallStatus.CANCELLED ->
            "Cancelled"
    }
}

fun automatedDiagnosticsShouldAutoExpand(
    status: AutomatedDiagnosticStepStatus
): Boolean {
    return status == AutomatedDiagnosticStepStatus.RUNNING ||
        status == AutomatedDiagnosticStepStatus.FAIL ||
        status == AutomatedDiagnosticStepStatus.BLOCKED
}

fun automatedDiagnosticsShouldAutoExpand(
    step: AutomatedDiagnosticStepResult
): Boolean {
    return automatedDiagnosticsShouldAutoExpand(step.status)
}

fun automatedDiagnosticsCurrentRequiredActionStepOrNull(
    state: AutomatedDiagnosticsRunState
): AutomatedDiagnosticStepResult? {
    return state.steps.firstOrNull { step ->
        step.requiredAction != null &&
            (
                step.status == AutomatedDiagnosticStepStatus.BLOCKED ||
                    step.status == AutomatedDiagnosticStepStatus.RUNNING
                )
    }
}

internal fun automatedDiagnosticsPreparationState(
    snapshot: AutomatedDiagnosticsRuntimeSnapshot
): AutomatedDiagnosticsPreparationState {
    val bluetoothPermissionsReady = snapshot.bluetoothPermissionStatus.allRequiredGranted
    val bluetoothEnabled = snapshot.bluetoothPermissionStatus.isBluetoothEnabled == true
    val locationEnabled = snapshot.bluetoothPermissionStatus.isLocationEnabled == true
    val wifiDirectSupported = snapshot.wifiDirectRuntimeStatus.permissionStatus.isWifiDirectSupported
    val wifiDirectPermissionsReady =
        snapshot.wifiDirectRuntimeStatus.permissionStatus.allRequiredGranted
    val wifiEnabled =
        snapshot.wifiDirectRuntimeStatus.permissionStatus.enabledState ==
            WifiDirectEnabledState.ENABLED
    val runtimeHosted = snapshot.runtimeEvidence.bleRuntimeHosted
    val javaNetRuntimeEnabled = snapshot.hybridBootstrapJavaNetRuntimeEnabled
    val desiredAvailabilityReady =
        snapshot.desiredAvailability == AuroraAvailabilityPreference.ONLINE

    val requiredAction = when {
        !bluetoothPermissionsReady ->
            AutomatedDiagnosticsRequiredAction(
                kind = AutomatedDiagnosticsRequiredActionKind.REQUEST_BLUETOOTH_PERMISSIONS,
                title = "Bluetooth permissions required",
                buttonLabel = "Grant Bluetooth permissions"
            )
        !bluetoothEnabled ->
            AutomatedDiagnosticsRequiredAction(
                kind = AutomatedDiagnosticsRequiredActionKind.OPEN_BLUETOOTH_SETTINGS,
                title = "Bluetooth is disabled",
                buttonLabel = "Open Bluetooth settings"
            )
        !locationEnabled ->
            AutomatedDiagnosticsRequiredAction(
                kind = AutomatedDiagnosticsRequiredActionKind.OPEN_LOCATION_SETTINGS,
                title = "Location/GPS is disabled",
                buttonLabel = "Open Location settings"
            )
        !wifiDirectSupported -> null
        !wifiDirectPermissionsReady ->
            AutomatedDiagnosticsRequiredAction(
                kind = AutomatedDiagnosticsRequiredActionKind.REQUEST_WIFI_DIRECT_PERMISSIONS,
                title = "Wi-Fi Direct permission required",
                buttonLabel = "Grant Wi-Fi Direct permission"
            )
        snapshot.wifiDirectRuntimeStatus.permissionStatus.enabledState ==
            WifiDirectEnabledState.DISABLED ->
            AutomatedDiagnosticsRequiredAction(
                kind = AutomatedDiagnosticsRequiredActionKind.OPEN_WIFI_SETTINGS,
                title = "Wi-Fi is disabled",
                buttonLabel = "Open Wi-Fi settings"
            )
        else -> null
    }

    val isReady =
        bluetoothPermissionsReady &&
            bluetoothEnabled &&
            locationEnabled &&
            wifiDirectSupported &&
            wifiDirectPermissionsReady &&
            wifiEnabled &&
            runtimeHosted &&
            javaNetRuntimeEnabled &&
            desiredAvailabilityReady

    val summary = when {
        isReady -> "Ready to start automated diagnostics."
        requiredAction?.kind == AutomatedDiagnosticsRequiredActionKind.REQUEST_BLUETOOTH_PERMISSIONS ->
            "Requesting Bluetooth permissions before starting the test."
        requiredAction?.kind == AutomatedDiagnosticsRequiredActionKind.REQUEST_WIFI_DIRECT_PERMISSIONS ->
            "Requesting Wi-Fi Direct permission before starting the test."
        !wifiDirectSupported -> "Wi-Fi Direct is unsupported on this device."
        !wifiEnabled -> "Enable Wi-Fi before starting the test."
        !runtimeHosted -> "Waiting for the BLE runtime host."
        !javaNetRuntimeEnabled -> "JavaNet runtime is unavailable."
        !desiredAvailabilityReady -> "Set availability to Online before starting the test."
        else -> "Preparing automated test."
    }

    return AutomatedDiagnosticsPreparationState(
        isReady = isReady,
        summary = summary,
        requiredAction = requiredAction,
        items = listOf(
            AutomatedDiagnosticsPreparationItem(
                label = "Bluetooth permissions",
                status = if (bluetoothPermissionsReady) {
                    AutomatedDiagnosticsPreparationItemStatus.READY
                } else {
                    AutomatedDiagnosticsPreparationItemStatus.WAITING
                },
                detail = if (bluetoothPermissionsReady) "Ready" else "Waiting"
            ),
            AutomatedDiagnosticsPreparationItem(
                label = "Wi-Fi Direct permission",
                status = if (!wifiDirectSupported || wifiDirectPermissionsReady) {
                    AutomatedDiagnosticsPreparationItemStatus.READY
                } else {
                    AutomatedDiagnosticsPreparationItemStatus.WAITING
                },
                detail = when {
                    !wifiDirectSupported -> "Unsupported"
                    wifiDirectPermissionsReady -> "Ready"
                    else -> "Waiting"
                }
            ),
            AutomatedDiagnosticsPreparationItem(
                label = "Bluetooth",
                status = if (bluetoothEnabled) {
                    AutomatedDiagnosticsPreparationItemStatus.READY
                } else {
                    AutomatedDiagnosticsPreparationItemStatus.WAITING
                },
                detail = if (bluetoothEnabled) "Enabled" else "Waiting"
            ),
            AutomatedDiagnosticsPreparationItem(
                label = "Location/GPS",
                status = if (locationEnabled) {
                    AutomatedDiagnosticsPreparationItemStatus.READY
                } else {
                    AutomatedDiagnosticsPreparationItemStatus.WAITING
                },
                detail = if (locationEnabled) "Enabled" else "Waiting"
            ),
            AutomatedDiagnosticsPreparationItem(
                label = "Wi-Fi",
                status = if (!wifiDirectSupported || wifiEnabled) {
                    AutomatedDiagnosticsPreparationItemStatus.READY
                } else {
                    AutomatedDiagnosticsPreparationItemStatus.WAITING
                },
                detail = when {
                    !wifiDirectSupported -> "Unsupported"
                    wifiEnabled -> "Enabled"
                    else -> "Waiting"
                }
            ),
            AutomatedDiagnosticsPreparationItem(
                label = "Runtime hosted",
                status = if (runtimeHosted) {
                    AutomatedDiagnosticsPreparationItemStatus.READY
                } else {
                    AutomatedDiagnosticsPreparationItemStatus.WAITING
                },
                detail = if (runtimeHosted) "Ready" else "Waiting"
            ),
            AutomatedDiagnosticsPreparationItem(
                label = "JavaNet runtime",
                status = if (javaNetRuntimeEnabled) {
                    AutomatedDiagnosticsPreparationItemStatus.READY
                } else {
                    AutomatedDiagnosticsPreparationItemStatus.WAITING
                },
                detail = if (javaNetRuntimeEnabled) "Ready" else "Waiting"
            )
        )
    )
}

internal fun automatedDiagnosticsPreparationCommand(
    isPreparationPending: Boolean,
    currentOverallStatus: AutomatedDiagnosticsOverallStatus,
    preparationState: AutomatedDiagnosticsPreparationState,
    bluetoothPermissionRequestAttempted: Boolean,
    wifiDirectPermissionRequestAttempted: Boolean,
    startWhenReady: Boolean = true
): AutomatedDiagnosticsPreparationCommand {
    if (!isPreparationPending || currentOverallStatus == AutomatedDiagnosticsOverallStatus.RUNNING) {
        return AutomatedDiagnosticsPreparationCommand.NONE
    }
    if (preparationState.isReady) {
        return if (startWhenReady) {
            AutomatedDiagnosticsPreparationCommand.START_RUN
        } else {
            AutomatedDiagnosticsPreparationCommand.NONE
        }
    }
    return when (preparationState.requiredAction?.kind) {
        AutomatedDiagnosticsRequiredActionKind.REQUEST_BLUETOOTH_PERMISSIONS ->
            if (bluetoothPermissionRequestAttempted) {
                AutomatedDiagnosticsPreparationCommand.NONE
            } else {
                AutomatedDiagnosticsPreparationCommand.REQUEST_BLUETOOTH_PERMISSIONS
            }
        AutomatedDiagnosticsRequiredActionKind.REQUEST_WIFI_DIRECT_PERMISSIONS ->
            if (wifiDirectPermissionRequestAttempted) {
                AutomatedDiagnosticsPreparationCommand.NONE
            } else {
                AutomatedDiagnosticsPreparationCommand.REQUEST_WIFI_DIRECT_PERMISSIONS
            }
        else -> AutomatedDiagnosticsPreparationCommand.NONE
    }
}

fun automatedDiagnosticsPlainTextReport(
    overallStatus: AutomatedDiagnosticsOverallStatus,
    selectedPeerId: String?,
    localPeerRole: AutomatedDiagnosticsPeerRole?,
    localRunnerExecutionId: String? = null,
    sharedRunId: String? = null,
    sharedRunCoordinatorPeerId: String? = null,
    sharedRunParticipantPeerId: String? = null,
    sharedRunSessionAssociationId: String? = null,
    sharedRunCreatedAtMillis: Long? = null,
    sharedRunExpiresAtMillis: Long? = null,
    sharedRunCanonicalPeerPair: String? = null,
    elapsedMillis: Long,
    steps: List<AutomatedDiagnosticStepResult>,
    phaseTwoSummary: String,
    reportTitle: String = "Automated Aurora Test"
): String {
    val headerLines = buildList {
        add(reportTitle)
        add("Overall: ${overallStatus.name}")
        add("Elapsed: ${formatAutomatedDiagnosticsDuration(elapsedMillis)}")
        add("Peer: ${selectedPeerId ?: "none"}")
        add("Role: ${localPeerRole?.name ?: "unknown"}")
        add("Local runner execution id: ${localRunnerExecutionId ?: "none"}")
        add("Shared run: ${sharedRunId ?: "none"}")
        add("Coordinator peer: ${sharedRunCoordinatorPeerId ?: "none"}")
        add("Participant peer: ${sharedRunParticipantPeerId ?: "none"}")
        add("Canonical peer pair: ${sharedRunCanonicalPeerPair ?: "none"}")
        add("Session association: ${sharedRunSessionAssociationId ?: "none"}")
        add("Run created: ${sharedRunCreatedAtMillis?.toString() ?: "none"}")
        add("Active run lease expires: ${sharedRunExpiresAtMillis?.toString() ?: "none"}")
        add("")
    }
    val stepLines = steps.flatMap { step ->
        buildList {
            add(
                "${step.visibleStepNumber.toString().padStart(2, '0')} " +
                    "${step.title} - ${step.status.name}: ${step.summary}"
            )
            step.waitingProgressText?.let { add("  Waiting: $it") }
            step.stabilizationProgressText?.let { add("  Stabilizing: $it") }
            step.blockerOrFailure?.let { add("  Blocker: $it") }
            if (step.retryCount > 0) {
                add("  Retries: ${step.retryCount}")
            }
            step.evidenceValues.forEach { evidence ->
                add("  ${evidence.label}: ${evidence.value}")
            }
            step.technicalDetails.forEach { detail ->
                add("  Detail: $detail")
            }
        }
    }

    val footerLines = if (phaseTwoSummary.isBlank()) {
        emptyList()
    } else {
        listOf("", phaseTwoSummary)
    }

    return (headerLines + stepLines + footerLines).joinToString(separator = "\n")
}

fun automatedDiagnosticsPhaseForStep(
    step: AutomatedDiagnosticStepResult
): AutomatedDiagnosticsPhase {
    return automatedDiagnosticsPhaseForStep(step.stepId)
}

fun automatedDiagnosticsPhaseForStep(
    stepId: AutomatedDiagnosticStepId
): AutomatedDiagnosticsPhase {
    return AutomatedDiagnosticsPhase.entries.first { phase ->
        stepId.stepNumber in phase.stepRange
    }
}

fun automatedDiagnosticsStepsForPhase(
    steps: List<AutomatedDiagnosticStepResult>,
    phase: AutomatedDiagnosticsPhase
): List<AutomatedDiagnosticStepResult> {
    return steps.filter { automatedDiagnosticsPhaseForStep(it) == phase }
}

fun automatedDiagnosticsPhaseStatus(
    steps: List<AutomatedDiagnosticStepResult>
): AutomatedDiagnosticStepStatus {
    return when {
        steps.any { it.status == AutomatedDiagnosticStepStatus.FAIL } ->
            AutomatedDiagnosticStepStatus.FAIL
        steps.any { it.status == AutomatedDiagnosticStepStatus.BLOCKED } ->
            AutomatedDiagnosticStepStatus.BLOCKED
        steps.any { it.status == AutomatedDiagnosticStepStatus.RUNNING } ->
            AutomatedDiagnosticStepStatus.RUNNING
        steps.all { it.status == AutomatedDiagnosticStepStatus.PASS } ->
            AutomatedDiagnosticStepStatus.PASS
        steps.any { it.status == AutomatedDiagnosticStepStatus.CANCELLED } ->
            AutomatedDiagnosticStepStatus.CANCELLED
        steps.any { it.status == AutomatedDiagnosticStepStatus.WAITING } ->
            AutomatedDiagnosticStepStatus.WAITING
        steps.any { it.status == AutomatedDiagnosticStepStatus.SKIPPED } ->
            AutomatedDiagnosticStepStatus.SKIPPED
        else -> AutomatedDiagnosticStepStatus.WAITING
    }
}

fun automatedDiagnosticsPhaseReport(
    state: AutomatedDiagnosticsRunState,
    phase: AutomatedDiagnosticsPhase
): String {
    val phaseSteps = automatedDiagnosticsStepsForPhase(state.steps, phase)
    return automatedDiagnosticsPlainTextReport(
        overallStatus = state.overallStatus,
        selectedPeerId = state.selectedPeerId,
        localPeerRole = state.localPeerRole,
        localRunnerExecutionId = state.localRunnerExecutionId,
        sharedRunId = state.sharedRunId,
        sharedRunCoordinatorPeerId = state.sharedRunCoordinatorPeerId,
        sharedRunParticipantPeerId = state.sharedRunParticipantPeerId,
        sharedRunSessionAssociationId = state.sharedRunSessionAssociationId,
        sharedRunCreatedAtMillis = state.sharedRunCreatedAtMillis,
        sharedRunExpiresAtMillis = state.sharedRunExpiresAtMillis,
        sharedRunCanonicalPeerPair = state.sharedRunCanonicalPeerPair,
        elapsedMillis = state.elapsedMillis,
        steps = phaseSteps,
        phaseTwoSummary = if (phase == AutomatedDiagnosticsPhase.PHASE_2) {
            state.phaseTwoSummary
        } else {
            ""
        },
        reportTitle = "Automated Aurora Test - ${phase.reportTitle}"
    )
}

fun automatedDiagnosticsPhaseSections(
    state: AutomatedDiagnosticsRunState
): List<AutomatedDiagnosticsPhaseSection> {
    return AutomatedDiagnosticsPhase.entries.map { phase ->
        val phaseSteps = automatedDiagnosticsStepsForPhase(state.steps, phase)
        AutomatedDiagnosticsPhaseSection(
            phase = phase,
            steps = phaseSteps,
            aggregatedStatus = automatedDiagnosticsPhaseStatus(phaseSteps),
            reportText = automatedDiagnosticsPhaseReport(state, phase)
        )
    }
}

fun formatAutomatedDiagnosticsDuration(
    durationMillis: Long
): String {
    val boundedDuration = durationMillis.coerceAtLeast(0L)
    val totalSeconds = boundedDuration / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val millis = boundedDuration % 1_000L
    return if (minutes > 0L) {
        "${minutes}m ${seconds}s"
    } else {
        "${seconds}.${millis.toString().padStart(3, '0')}s"
    }
}
