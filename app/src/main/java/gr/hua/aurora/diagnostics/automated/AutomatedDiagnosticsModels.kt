package gr.hua.aurora.diagnostics.automated

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
    HYBRID_BOOTSTRAP_TRIGGER(17, "Hybrid bootstrap trigger")
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
    val phaseTwoSummary: String = automatedDiagnosticsPhaseTwoSummary,
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
        require(phaseTwoSummary.isNotBlank()) {
            "Automated diagnostics phaseTwoSummary must not be blank."
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
                    phaseTwoSummary = automatedDiagnosticsPhaseTwoSummary
                )
            )
        }
    }
}

const val automatedDiagnosticsPhaseTwoSummary: String =
    "Phase 2 automates Wi-Fi Direct discovery/group, socket setup, bridges, and hybrid bootstrap validation. Global and Private probe messages remain for the next phase."

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
    step: AutomatedDiagnosticStepResult
): Boolean {
    return step.status == AutomatedDiagnosticStepStatus.RUNNING ||
        step.status == AutomatedDiagnosticStepStatus.FAIL ||
        step.status == AutomatedDiagnosticStepStatus.BLOCKED
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
    phaseTwoSummary: String
): String {
    val headerLines = buildList {
        add("Automated Aurora Test")
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

    return (headerLines + stepLines + listOf("", phaseTwoSummary)).joinToString(separator = "\n")
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
