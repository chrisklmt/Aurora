package gr.hua.aurora.diagnostics.automated

import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlanBuilder
import gr.hua.aurora.protocol.MessageFrameType

private const val automatedDiagnosticsApplicationProbePrefix = "AURORA_DIAG"

enum class AutomatedDiagnosticsApplicationProbeKind {
    GLOBAL,
    PRIVATE
}

enum class AutomatedDiagnosticsApplicationProbeDirection {
    C2P,
    P2C
}

enum class AutomatedDiagnosticsApplicationProbeAcceptanceResult {
    APPENDED
}

data class AutomatedDiagnosticsPhaseApplicationProbeDescriptor(
    val probeKind: AutomatedDiagnosticsApplicationProbeKind,
    val messageId: String,
    val transportStatus: String? = null,
    val localBleTransportResult: String? = null,
    val expectedTransportGroupId: Int? = null,
    val expectedChunkCount: Int? = null,
    val frameByteCount: Int? = null,
    val senderChunksQueued: Int? = null,
    val senderChunksWriteAttempted: Int? = null,
    val senderLastLocalWriteResult: String? = null
) {
    init {
        require(messageId.isNotBlank()) {
            "Automated diagnostics phase probe descriptor messageId must not be blank."
        }
        require(transportStatus?.isBlank() != true) {
            "Automated diagnostics phase probe descriptor transportStatus must not be blank when provided."
        }
        require(localBleTransportResult?.isBlank() != true) {
            "Automated diagnostics phase probe descriptor localBleTransportResult must not be blank when provided."
        }
        require(expectedTransportGroupId == null || expectedTransportGroupId >= 0) {
            "Automated diagnostics phase probe descriptor expectedTransportGroupId must be non-negative when provided."
        }
        require(expectedChunkCount == null || expectedChunkCount > 0) {
            "Automated diagnostics phase probe descriptor expectedChunkCount must be positive when provided."
        }
        require(frameByteCount == null || frameByteCount > 0) {
            "Automated diagnostics phase probe descriptor frameByteCount must be positive when provided."
        }
        require(senderChunksQueued == null || senderChunksQueued >= 0) {
            "Automated diagnostics phase probe descriptor senderChunksQueued must be non-negative when provided."
        }
        require(senderChunksWriteAttempted == null || senderChunksWriteAttempted >= 0) {
            "Automated diagnostics phase probe descriptor senderChunksWriteAttempted must be non-negative when provided."
        }
        require(senderLastLocalWriteResult?.isBlank() != true) {
            "Automated diagnostics phase probe descriptor senderLastLocalWriteResult must not be blank when provided."
        }
        require(
            expectedChunkCount != null ||
                (senderChunksQueued == null && senderChunksWriteAttempted == null)
        ) {
            "Automated diagnostics phase probe descriptor sender chunk counters require expectedChunkCount."
        }
        require(senderChunksQueued == null || senderChunksQueued <= expectedChunkCount!!) {
            "Automated diagnostics phase probe descriptor senderChunksQueued must not exceed expectedChunkCount."
        }
        require(
            senderChunksWriteAttempted == null ||
                senderChunksWriteAttempted <= expectedChunkCount!!
        ) {
            "Automated diagnostics phase probe descriptor senderChunksWriteAttempted must not exceed expectedChunkCount."
        }
    }
}

data class AutomatedDiagnosticsApplicationProbeMarker(
    val sharedRunId: String,
    val stepId: AutomatedDiagnosticStepId,
    val attemptNumber: Int,
    val probeKind: AutomatedDiagnosticsApplicationProbeKind,
    val direction: AutomatedDiagnosticsApplicationProbeDirection
) {
    init {
        require(sharedRunId.isNotBlank()) {
            "Automated diagnostics probe sharedRunId must not be blank."
        }
        require(stepId in automatedDiagnosticsApplicationProbeStepIds) {
            "Automated diagnostics probe step must be a Phase 3 messaging step."
        }
        require(attemptNumber > 0) {
            "Automated diagnostics probe attemptNumber must be positive."
        }
    }

    fun bodyText(): String {
        return listOf(
            automatedDiagnosticsApplicationProbePrefix,
            sharedRunId,
            stepId.stepNumber.toString(),
            attemptNumber.toString(),
            probeKind.name,
            direction.name
        ).joinToString(separator = "|")
    }
}

data class AutomatedDiagnosticsApplicationProbeObservation(
    val sharedRunId: String,
    val stepId: AutomatedDiagnosticStepId,
    val attemptNumber: Int,
    val probeKind: AutomatedDiagnosticsApplicationProbeKind,
    val direction: AutomatedDiagnosticsApplicationProbeDirection,
    val messageId: String,
    val senderPeerId: String,
    val applicationSenderId: String,
    val receiverPeerId: String,
    val messageType: MessageFrameType,
    val threadId: String,
    val privateChatId: String?,
    val transportGroupId: Int? = null,
    val marker: AutomatedDiagnosticsApplicationProbeMarker,
    val observedAtMonotonicMillis: Long,
    val observedAtWallClockMillis: Long? = null,
    val acceptanceResult: AutomatedDiagnosticsApplicationProbeAcceptanceResult =
        AutomatedDiagnosticsApplicationProbeAcceptanceResult.APPENDED
) {
    init {
        require(sharedRunId.isNotBlank()) {
            "Automated diagnostics probe observation sharedRunId must not be blank."
        }
        require(messageId.isNotBlank()) {
            "Automated diagnostics probe observation messageId must not be blank."
        }
        require(senderPeerId.isNotBlank()) {
            "Automated diagnostics probe observation senderPeerId must not be blank."
        }
        require(applicationSenderId.isNotBlank()) {
            "Automated diagnostics probe observation applicationSenderId must not be blank."
        }
        require(receiverPeerId.isNotBlank()) {
            "Automated diagnostics probe observation receiverPeerId must not be blank."
        }
        require(threadId.isNotBlank()) {
            "Automated diagnostics probe observation threadId must not be blank."
        }
        require(transportGroupId == null || transportGroupId >= 0) {
            "Automated diagnostics probe observation transportGroupId must be non-negative when provided."
        }
        require(observedAtMonotonicMillis >= 0L) {
            "Automated diagnostics probe observation observedAtMonotonicMillis must be non-negative."
        }
    }
}

enum class AutomatedDiagnosticsApplicationProbeSourceResolutionSource {
    EXACT_ACTIVE_ADDRESS,
    EXACT_DISCOVERED_ADDRESS,
    CURRENT_RUN_DIAGNOSTICS_ASSOCIATION,
    UNRESOLVED
}

enum class AutomatedDiagnosticsApplicationProbeAssociationOutcome {
    RESOLVED,
    NO_ASSOCIATION_FOR_SOURCE_ADDRESS,
    ASSOCIATION_WRONG_RUN,
    ASSOCIATION_WRONG_STEP,
    ASSOCIATION_WRONG_ATTEMPT,
    ASSOCIATION_WRONG_PEER,
    ASSOCIATION_WRONG_RECEIVER
}

enum class AutomatedDiagnosticsApplicationProbeSelectedSecurePeerGate {
    MATCH,
    SELECTED_SECURE_PEER_UNAVAILABLE,
    SELECTED_SECURE_PEER_MISMATCH
}

data class AutomatedDiagnosticsApplicationProbeSourceResolution(
    val sourceDeviceAddress: String?,
    val exactAddressSourcePeerId: String?,
    val diagnosticsAssociatedSourcePeerId: String?,
    val resolvedSourcePeerId: String?,
    val resolutionSource: AutomatedDiagnosticsApplicationProbeSourceResolutionSource,
    val associationLookupHit: Boolean,
    val storedAssociationPeerId: String?,
    val storedAssociationSharedRunId: String?,
    val storedAssociationStepId: AutomatedDiagnosticStepId?,
    val storedAssociationAttemptNumber: Int?,
    val storedAssociationExpectedRemotePeerId: String?,
    val selectedSecurePeerId: String?,
    val diagnosticsAssociationOutcome: AutomatedDiagnosticsApplicationProbeAssociationOutcome?,
    val selectedSecurePeerGate: AutomatedDiagnosticsApplicationProbeSelectedSecurePeerGate
)

data class AutomatedDiagnosticsApplicationProbeReceiveDiagnostic(
    val sharedRunId: String,
    val stepId: AutomatedDiagnosticStepId,
    val attemptNumber: Int,
    val probeKind: AutomatedDiagnosticsApplicationProbeKind,
    val direction: AutomatedDiagnosticsApplicationProbeDirection,
    val messageId: String,
    val applicationSenderId: String,
    val receiverPeerId: String?,
    val messageType: MessageFrameType,
    val threadId: String,
    val privateChatId: String?,
    val transportGroupId: Int? = null,
    val marker: AutomatedDiagnosticsApplicationProbeMarker,
    val sourceResolution: AutomatedDiagnosticsApplicationProbeSourceResolution,
    val observedAtMonotonicMillis: Long,
    val observedAtWallClockMillis: Long? = null,
    val acceptanceResult: AutomatedDiagnosticsApplicationProbeAcceptanceResult =
        AutomatedDiagnosticsApplicationProbeAcceptanceResult.APPENDED
) {
    init {
        require(sharedRunId.isNotBlank()) {
            "Automated diagnostics probe receive diagnostic sharedRunId must not be blank."
        }
        require(messageId.isNotBlank()) {
            "Automated diagnostics probe receive diagnostic messageId must not be blank."
        }
        require(applicationSenderId.isNotBlank()) {
            "Automated diagnostics probe receive diagnostic applicationSenderId must not be blank."
        }
        require(threadId.isNotBlank()) {
            "Automated diagnostics probe receive diagnostic threadId must not be blank."
        }
        require(transportGroupId == null || transportGroupId >= 0) {
            "Automated diagnostics probe receive diagnostic transportGroupId must be non-negative when provided."
        }
        require(observedAtMonotonicMillis >= 0L) {
            "Automated diagnostics probe receive diagnostic observedAtMonotonicMillis must be non-negative."
        }
    }
}

data class AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
    val groupId: Int?,
    val sourceDeviceAddress: String?,
    val observedAtMonotonicMillis: Long,
    val observedAtWallClockMillis: Long? = null,
    val transportResultKind: String,
    val receivedChunks: Int? = null,
    val expectedChunks: Int? = null,
    val processingResultKind: String? = null,
    val receiveFailureKind: String? = null,
    val ingestionResultKind: String? = null,
    val failureDetail: String? = null,
    val messageId: String? = null,
    val messageType: MessageFrameType? = null,
    val marker: AutomatedDiagnosticsApplicationProbeMarker? = null,
    val expectedMessageType: MessageFrameType? = null,
    val messageTypeMatchedExpectedProbe: Boolean? = null
) {
    init {
        require(transportResultKind.isNotBlank()) {
            "Automated diagnostics application probe transportResultKind must not be blank."
        }
        require(observedAtMonotonicMillis >= 0L) {
            "Automated diagnostics application probe transport event observedAtMonotonicMillis must be non-negative."
        }
        require(sourceDeviceAddress?.isBlank() != true) {
            "Automated diagnostics application probe sourceDeviceAddress must not be blank when provided."
        }
        require(receivedChunks == null || receivedChunks >= 0) {
            "Automated diagnostics application probe receivedChunks must be non-negative when provided."
        }
        require(expectedChunks == null || expectedChunks >= 0) {
            "Automated diagnostics application probe expectedChunks must be non-negative when provided."
        }
        require(receiveFailureKind?.isBlank() != true) {
            "Automated diagnostics application probe receiveFailureKind must not be blank when provided."
        }
        require(ingestionResultKind?.isBlank() != true) {
            "Automated diagnostics application probe ingestionResultKind must not be blank when provided."
        }
        require(failureDetail?.isBlank() != true) {
            "Automated diagnostics application probe failureDetail must not be blank when provided."
        }
        require(messageId?.isBlank() != true) {
            "Automated diagnostics application probe messageId must not be blank when provided."
        }
    }
}

data class AutomatedDiagnosticsRawBleGroupSummary(
    val groupId: Int,
    val receivedChunks: Int? = null,
    val expectedChunks: Int? = null,
    val latestTransportResultKind: String,
    val latestObservedAtMonotonicMillis: Long,
    val completeFrameSeen: Boolean = false
) {
    init {
        require(groupId >= 0) {
            "Automated diagnostics raw BLE group summary groupId must be non-negative."
        }
        require(receivedChunks == null || receivedChunks >= 0) {
            "Automated diagnostics raw BLE group summary receivedChunks must be non-negative when provided."
        }
        require(expectedChunks == null || expectedChunks >= 0) {
            "Automated diagnostics raw BLE group summary expectedChunks must be non-negative when provided."
        }
        require(latestTransportResultKind.isNotBlank()) {
            "Automated diagnostics raw BLE group summary latestTransportResultKind must not be blank."
        }
        require(latestObservedAtMonotonicMillis >= 0L) {
            "Automated diagnostics raw BLE group summary latestObservedAtMonotonicMillis must be non-negative."
        }
    }
}

fun automatedDiagnosticsApplicationProbeMarkerOrNull(
    body: String
): AutomatedDiagnosticsApplicationProbeMarker? {
    val parts = body.trim().split('|')
    if (parts.size != 6 || parts.firstOrNull() != automatedDiagnosticsApplicationProbePrefix) {
        return null
    }

    val sharedRunId = parts[1].trim().takeIf { it.isNotEmpty() } ?: return null
    val stepNumber = parts[2].trim().toIntOrNull() ?: return null
    val stepId = automatedDiagnosticsApplicationProbeStepIds.firstOrNull { step ->
        step.stepNumber == stepNumber
    } ?: return null
    val attemptNumber = parts[3].trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
    val probeKind = runCatching {
        AutomatedDiagnosticsApplicationProbeKind.valueOf(parts[4].trim())
    }.getOrNull() ?: return null
    val direction = runCatching {
        AutomatedDiagnosticsApplicationProbeDirection.valueOf(parts[5].trim())
    }.getOrNull() ?: return null

    return AutomatedDiagnosticsApplicationProbeMarker(
        sharedRunId = sharedRunId,
        stepId = stepId,
        attemptNumber = attemptNumber,
        probeKind = probeKind,
        direction = direction
    )
}

fun automatedDiagnosticsApplicationProbeFingerprint(
    marker: AutomatedDiagnosticsApplicationProbeMarker
): String {
    return listOf(
        marker.sharedRunId,
        marker.stepId.stepNumber.toString(),
        marker.attemptNumber.toString(),
        marker.probeKind.name,
        marker.direction.name
    ).joinToString(separator = "|")
}

fun automatedDiagnosticsApplicationProbeExpectedMessageType(
    probeKind: AutomatedDiagnosticsApplicationProbeKind
): MessageFrameType {
    return when (probeKind) {
        AutomatedDiagnosticsApplicationProbeKind.GLOBAL -> MessageFrameType.GLOBAL_TEXT
        AutomatedDiagnosticsApplicationProbeKind.PRIVATE -> MessageFrameType.PRIVATE_TEXT
    }
}

fun automatedDiagnosticsApplicationProbeExpectedTransportGroupId(
    messageId: String,
    receiverPeerId: String
): Int {
    return OutgoingBleTransportSendPlanBuilder.deriveGroupId(
        messageId = messageId,
        targetPeerId = receiverPeerId
    )
}

fun automatedDiagnosticsApplicationProbeTransportEventsWithinWindow(
    events: List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent>,
    minimumObservedAtMillis: Long
): List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent> {
    return events.filter { event ->
        event.observedAtMonotonicMillis >= minimumObservedAtMillis
    }
}

fun automatedDiagnosticsApplicationProbeMatchingTransportEvents(
    events: List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent>,
    minimumObservedAtMillis: Long,
    expectedGroupId: Int?
): List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent> {
    if (expectedGroupId == null) {
        return emptyList()
    }
    return automatedDiagnosticsApplicationProbeTransportEventsWithinWindow(
        events = events,
        minimumObservedAtMillis = minimumObservedAtMillis
    ).filter { event ->
        event.groupId == expectedGroupId
    }
}

fun automatedDiagnosticsApplicationProbeReceiverFrameStatus(
    expectedGroupId: Int?,
    matchingEvents: List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent>
): String {
    if (expectedGroupId == null) {
        return "CORRELATION_UNAVAILABLE"
    }
    if (matchingEvents.isEmpty()) {
        return "NO_MATCHING_CHUNK_SEEN"
    }
    if (
        matchingEvents.any { event ->
            event.transportResultKind == "Processed" ||
                event.transportResultKind == "ProcessorFailed"
        }
    ) {
        return "COMPLETE_FRAME_SEEN"
    }
    val partialEvent = matchingEvents
        .filter { event ->
            event.receivedChunks != null && event.expectedChunks != null
        }
        .maxWithOrNull(
            compareBy<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent> {
                it.receivedChunks ?: -1
            }.thenBy {
                it.observedAtMonotonicMillis
            }
        )
    return if (partialEvent?.receivedChunks != null && partialEvent.expectedChunks != null) {
        "PARTIAL_FRAME_${partialEvent.receivedChunks}_OF_${partialEvent.expectedChunks}"
    } else {
        "MATCHING_CHUNK_SEEN_BUFFERED"
    }
}

fun automatedDiagnosticsApplicationProbeMatchingChunkCount(
    matchingEvents: List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent>,
    expectedChunkCount: Int?
): Int {
    if (
        expectedChunkCount != null &&
            matchingEvents.any { event ->
                event.transportResultKind == "Processed" ||
                    event.transportResultKind == "ProcessorFailed"
            }
    ) {
        return expectedChunkCount
    }
    val maxBufferedChunkCount = matchingEvents
        .mapNotNull { event -> event.receivedChunks }
        .maxOrNull()
    if (maxBufferedChunkCount != null) {
        return maxBufferedChunkCount
    }
    return 0
}

fun automatedDiagnosticsApplicationProbeExpectedChunkCount(
    descriptorExpectedChunkCount: Int?,
    matchingEvents: List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent>
): Int? {
    return descriptorExpectedChunkCount ?: matchingEvents
        .mapNotNull { event -> event.expectedChunks }
        .maxOrNull()
}

fun automatedDiagnosticsRawBleGroupSummaries(
    events: List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent>,
    minimumObservedAtMillis: Long,
    limit: Int = 4
): List<AutomatedDiagnosticsRawBleGroupSummary> {
    return automatedDiagnosticsApplicationProbeTransportEventsWithinWindow(
        events = events,
        minimumObservedAtMillis = minimumObservedAtMillis
    ).mapNotNull { event ->
        val groupId = event.groupId ?: return@mapNotNull null
        groupId to event
    }.groupBy(
        keySelector = { it.first },
        valueTransform = { it.second }
    ).map { (groupId, groupEvents) ->
        val latestEvent = groupEvents.maxByOrNull { event ->
            event.observedAtMonotonicMillis
        } ?: error("Group events must not be empty.")
        AutomatedDiagnosticsRawBleGroupSummary(
            groupId = groupId,
            receivedChunks = groupEvents.mapNotNull { event -> event.receivedChunks }.maxOrNull(),
            expectedChunks = groupEvents.mapNotNull { event -> event.expectedChunks }.maxOrNull(),
            latestTransportResultKind = latestEvent.transportResultKind,
            latestObservedAtMonotonicMillis = latestEvent.observedAtMonotonicMillis,
            completeFrameSeen = groupEvents.any { event ->
                event.transportResultKind == "Processed" ||
                    event.transportResultKind == "ProcessorFailed"
            }
        )
    }.sortedWith(
        compareByDescending<AutomatedDiagnosticsRawBleGroupSummary> {
            it.latestObservedAtMonotonicMillis
        }.thenByDescending {
            it.groupId
        }
    ).take(limit.coerceAtLeast(1))
}

fun automatedDiagnosticsRawBleGroupSummaryText(
    summaries: List<AutomatedDiagnosticsRawBleGroupSummary>
): String? {
    if (summaries.isEmpty()) {
        return null
    }
    return summaries.joinToString(separator = ", ") { summary ->
        val chunkText = when {
            summary.completeFrameSeen -> "COMPLETE"
            summary.receivedChunks != null && summary.expectedChunks != null ->
                "${summary.receivedChunks}/${summary.expectedChunks}"
            summary.receivedChunks != null ->
                "${summary.receivedChunks}/?"
            summary.latestTransportResultKind == "Processed" ||
                summary.latestTransportResultKind == "ProcessorFailed" ->
                "COMPLETE"
            else ->
                summary.latestTransportResultKind.lowercase()
        }
        "${summary.groupId}: $chunkText"
    }
}

internal val automatedDiagnosticsApplicationProbeStepIds = setOf(
    AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
    AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE,
    AutomatedDiagnosticStepId.REVERSE_DIRECTION_MESSAGING_PROBE
)
