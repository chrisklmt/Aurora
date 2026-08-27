package gr.hua.aurora.diagnostics.automated

import gr.hua.aurora.transport.hybrid.HybridTransportControlStore
import java.security.MessageDigest

private const val automatedDiagnosticsCorrelationTokenMinLength = 16
private const val automatedDiagnosticsCorrelationTokenMaxLength = 128
private const val automatedDiagnosticsPhaseProbeEntrySeparator = "\u001E"
private const val automatedDiagnosticsPhaseProbeFieldSeparator = "\u001F"
private val automatedDiagnosticsCorrelationTokenPattern =
    Regex("^[A-Za-z0-9_-]+$")

data class AutomatedDiagnosticsCanonicalPeerPair(
    val lowerPeerId: String,
    val higherPeerId: String
) {
    init {
        require(lowerPeerId.isNotBlank()) {
            "Automated diagnostics canonical lowerPeerId must not be blank."
        }
        require(higherPeerId.isNotBlank()) {
            "Automated diagnostics canonical higherPeerId must not be blank."
        }
        require(lowerPeerId <= higherPeerId) {
            "Automated diagnostics canonical peer pair must be sorted."
        }
    }

    fun contains(
        peerId: String
    ): Boolean {
        return peerId == lowerPeerId || peerId == higherPeerId
    }

    companion object {
        fun from(
            firstPeerId: String,
            secondPeerId: String
        ): AutomatedDiagnosticsCanonicalPeerPair {
            val sortedPeerIds = listOf(firstPeerId, secondPeerId).sorted()
            return AutomatedDiagnosticsCanonicalPeerPair(
                lowerPeerId = sortedPeerIds.first(),
                higherPeerId = sortedPeerIds.last()
            )
        }
    }
}

data class AutomatedDiagnosticsSharedRun(
    val runId: String,
    val coordinatorPeerId: String,
    val participantPeerId: String,
    val sessionAssociationId: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long
) {
    init {
        require(runId.isNotBlank()) {
            "Automated diagnostics shared runId must not be blank."
        }
        require(coordinatorPeerId.isNotBlank()) {
            "Automated diagnostics coordinatorPeerId must not be blank."
        }
        require(participantPeerId.isNotBlank()) {
            "Automated diagnostics participantPeerId must not be blank."
        }
        require(sessionAssociationId.isNotBlank()) {
            "Automated diagnostics sessionAssociationId must not be blank."
        }
        require(createdAtMillis >= 0L) {
            "Automated diagnostics shared run createdAtMillis must be non-negative."
        }
        require(expiresAtMillis >= createdAtMillis) {
            "Automated diagnostics shared run expiresAtMillis must be at least createdAtMillis."
        }
    }

    fun canonicalPeerPair(): AutomatedDiagnosticsCanonicalPeerPair {
        return AutomatedDiagnosticsCanonicalPeerPair.from(
            coordinatorPeerId,
            participantPeerId
        )
    }
}

data class AutomatedDiagnosticsRunAnnouncement(
    val sharedRun: AutomatedDiagnosticsSharedRun,
    val peerId: String,
    val createdAtMillis: Long = sharedRun.createdAtMillis
) {
    init {
        require(peerId.isNotBlank()) {
            "Automated diagnostics run announcement peerId must not be blank."
        }
        require(createdAtMillis >= 0L) {
            "Automated diagnostics run announcement createdAtMillis must be non-negative."
        }
    }
}

data class AutomatedDiagnosticsParticipantJoin(
    val sharedRun: AutomatedDiagnosticsSharedRun,
    val peerId: String,
    val createdAtMillis: Long
) {
    init {
        require(peerId.isNotBlank()) {
            "Automated diagnostics participant-join peerId must not be blank."
        }
        require(createdAtMillis >= 0L) {
            "Automated diagnostics participant-join createdAtMillis must be non-negative."
        }
    }
}

data class AutomatedDiagnosticsServerReadySignal(
    val sharedRun: AutomatedDiagnosticsSharedRun,
    val peerId: String,
    val expectedClientPeerId: String,
    val groupOwnerAddress: String,
    val socketPort: Int,
    val serverToken: Long,
    val createdAtMillis: Long,
    val expiresAtMillis: Long
) {
    init {
        require(peerId.isNotBlank()) {
            "Automated diagnostics server-ready peerId must not be blank."
        }
        require(expectedClientPeerId.isNotBlank()) {
            "Automated diagnostics expectedClientPeerId must not be blank."
        }
        require(groupOwnerAddress.isNotBlank()) {
            "Automated diagnostics server-ready groupOwnerAddress must not be blank."
        }
        require(socketPort in 1..65535) {
            "Automated diagnostics server-ready socketPort must be within 1..65535."
        }
        require(serverToken >= 0L) {
            "Automated diagnostics server-ready serverToken must be non-negative."
        }
        require(createdAtMillis >= 0L) {
            "Automated diagnostics server-ready createdAtMillis must be non-negative."
        }
        require(expiresAtMillis >= createdAtMillis) {
            "Automated diagnostics server-ready expiresAtMillis must be at least createdAtMillis."
        }
    }
}

data class AutomatedDiagnosticsWifiDirectPeerReadySignal(
    val sharedRun: AutomatedDiagnosticsSharedRun,
    val peerId: String,
    val expectedRemotePeerId: String,
    val wifiDirectCorrelationToken: String,
    val wifiDirectDeviceName: String? = null,
    val createdAtMillis: Long,
    val expiresAtMillis: Long
) {
    init {
        require(peerId.isNotBlank()) {
            "Automated diagnostics Wi-Fi peer-ready peerId must not be blank."
        }
        require(expectedRemotePeerId.isNotBlank()) {
            "Automated diagnostics Wi-Fi peer-ready expectedRemotePeerId must not be blank."
        }
        require(
            wifiDirectCorrelationToken.length in
                automatedDiagnosticsCorrelationTokenMinLength..
                automatedDiagnosticsCorrelationTokenMaxLength
        ) {
            "Automated diagnostics Wi-Fi peer-ready correlation token must be within " +
                "$automatedDiagnosticsCorrelationTokenMinLength.." +
                "$automatedDiagnosticsCorrelationTokenMaxLength characters."
        }
        require(
            automatedDiagnosticsCorrelationTokenPattern.matches(
                wifiDirectCorrelationToken
            )
        ) {
            "Automated diagnostics Wi-Fi peer-ready correlation token must be URL-safe opaque text."
        }
        require(wifiDirectDeviceName?.isBlank() != true) {
            "Automated diagnostics Wi-Fi peer-ready device name must not be blank when provided."
        }
        require(createdAtMillis >= 0L) {
            "Automated diagnostics Wi-Fi peer-ready createdAtMillis must be non-negative."
        }
        require(expiresAtMillis >= createdAtMillis) {
            "Automated diagnostics Wi-Fi peer-ready expiresAtMillis must be at least createdAtMillis."
        }
    }
}

enum class AutomatedDiagnosticsPhaseState(
    val statusText: String
) {
    READY("READY"),
    RUNNING("RUNNING"),
    PASS("PASS"),
    FAIL("FAIL"),
    BLOCKED("BLOCKED"),
    CANCELLED("CANCELLED")
}

data class AutomatedDiagnosticsPhaseSignal(
    val sharedRun: AutomatedDiagnosticsSharedRun,
    val peerId: String,
    val expectedRemotePeerId: String,
    val stepId: AutomatedDiagnosticStepId,
    val phaseState: AutomatedDiagnosticsPhaseState,
    val attemptNumber: Int,
    val applicationProbeDescriptors: List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor> =
        emptyList(),
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val sourceDeviceAddress: String? = null
) {
    init {
        require(peerId.isNotBlank()) {
            "Automated diagnostics phase peerId must not be blank."
        }
        require(expectedRemotePeerId.isNotBlank()) {
            "Automated diagnostics phase expectedRemotePeerId must not be blank."
        }
        require(attemptNumber > 0) {
            "Automated diagnostics phase attemptNumber must be positive."
        }
        require(createdAtMillis >= 0L) {
            "Automated diagnostics phase createdAtMillis must be non-negative."
        }
        require(expiresAtMillis >= createdAtMillis) {
            "Automated diagnostics phase expiresAtMillis must be at least createdAtMillis."
        }
        require(sourceDeviceAddress?.isBlank() != true) {
            "Automated diagnostics phase sourceDeviceAddress must not be blank when provided."
        }
    }
}

private data class AutomatedDiagnosticsPhaseApplicationProbeDescriptorKey(
    val probeKind: AutomatedDiagnosticsApplicationProbeKind,
    val messageId: String
)

fun mergeAutomatedDiagnosticsPhaseSignal(
    current: AutomatedDiagnosticsPhaseSignal?,
    incoming: AutomatedDiagnosticsPhaseSignal
): AutomatedDiagnosticsPhaseSignal {
    val existing = current ?: return incoming
    if (!automatedDiagnosticsCanMergePhaseSignal(existing, incoming)) {
        return automatedDiagnosticsPreferredPhaseSignal(existing, incoming)
    }
    val preferred = automatedDiagnosticsPreferredPhaseSignal(existing, incoming)
    val fallback = if (preferred == existing) incoming else existing
    return preferred.copy(
        applicationProbeDescriptors = mergeAutomatedDiagnosticsPhaseApplicationProbeDescriptors(
            preferred = preferred.applicationProbeDescriptors,
            fallback = fallback.applicationProbeDescriptors
        ),
        expiresAtMillis = maxOf(existing.expiresAtMillis, incoming.expiresAtMillis),
        sourceDeviceAddress = preferred.sourceDeviceAddress ?: fallback.sourceDeviceAddress
    )
}

private fun automatedDiagnosticsCanMergePhaseSignal(
    first: AutomatedDiagnosticsPhaseSignal,
    second: AutomatedDiagnosticsPhaseSignal
): Boolean {
    return first.sharedRun == second.sharedRun &&
        first.peerId == second.peerId &&
        first.expectedRemotePeerId == second.expectedRemotePeerId &&
        first.stepId == second.stepId &&
        first.attemptNumber == second.attemptNumber
}

private fun automatedDiagnosticsPreferredPhaseSignal(
    first: AutomatedDiagnosticsPhaseSignal,
    second: AutomatedDiagnosticsPhaseSignal
): AutomatedDiagnosticsPhaseSignal {
    val comparison = compareValuesBy(
        first,
        second,
        { automatedDiagnosticsPhaseStatePriority(it.phaseState) },
        { it.createdAtMillis },
        { automatedDiagnosticsPhaseApplicationProbeDescriptorRichness(it.applicationProbeDescriptors) }
    )
    return if (comparison >= 0) {
        first
    } else {
        second
    }
}

private fun automatedDiagnosticsPhaseStatePriority(
    phaseState: AutomatedDiagnosticsPhaseState
): Int {
    return when (phaseState) {
        AutomatedDiagnosticsPhaseState.READY -> 0
        AutomatedDiagnosticsPhaseState.RUNNING -> 1
        AutomatedDiagnosticsPhaseState.PASS,
        AutomatedDiagnosticsPhaseState.FAIL,
        AutomatedDiagnosticsPhaseState.BLOCKED,
        AutomatedDiagnosticsPhaseState.CANCELLED -> 2
    }
}

private fun automatedDiagnosticsPhaseApplicationProbeDescriptorRichness(
    descriptors: List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor>
): Int {
    return descriptors.sumOf(::automatedDiagnosticsPhaseApplicationProbeDescriptorRichness)
}

private fun automatedDiagnosticsPhaseApplicationProbeDescriptorRichness(
    descriptor: AutomatedDiagnosticsPhaseApplicationProbeDescriptor
): Int {
    var richness = 1
    if (descriptor.transportStatus != null) {
        richness += 1
    }
    if (descriptor.localBleTransportResult != null) {
        richness += 1
    }
    if (descriptor.expectedTransportGroupId != null) {
        richness += 1
    }
    if (descriptor.expectedChunkCount != null) {
        richness += 1
    }
    if (descriptor.frameByteCount != null) {
        richness += 1
    }
    if (descriptor.senderChunksQueued != null) {
        richness += 1
    }
    if (descriptor.senderChunksWriteAttempted != null) {
        richness += 1
    }
    if (descriptor.senderLastLocalWriteResult != null) {
        richness += 1
    }
    return richness
}

private fun mergeAutomatedDiagnosticsPhaseApplicationProbeDescriptors(
    preferred: List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor>,
    fallback: List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor>
): List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor> {
    val mergedByKey =
        linkedMapOf<
            AutomatedDiagnosticsPhaseApplicationProbeDescriptorKey,
            AutomatedDiagnosticsPhaseApplicationProbeDescriptor
            >()
    preferred.forEach { descriptor ->
        mergedByKey[descriptor.phaseApplicationProbeDescriptorKey()] = descriptor
    }
    fallback.forEach { descriptor ->
        val key = descriptor.phaseApplicationProbeDescriptorKey()
        val existing = mergedByKey[key]
        mergedByKey[key] = if (existing == null) {
            descriptor
        } else {
            mergeAutomatedDiagnosticsPhaseApplicationProbeDescriptor(
                preferred = existing,
                fallback = descriptor
            )
        }
    }
    return mergedByKey.values.toList()
}

private fun mergeAutomatedDiagnosticsPhaseApplicationProbeDescriptor(
    preferred: AutomatedDiagnosticsPhaseApplicationProbeDescriptor,
    fallback: AutomatedDiagnosticsPhaseApplicationProbeDescriptor
): AutomatedDiagnosticsPhaseApplicationProbeDescriptor {
    return preferred.copy(
        transportStatus = preferred.transportStatus ?: fallback.transportStatus,
        localBleTransportResult =
            preferred.localBleTransportResult ?: fallback.localBleTransportResult,
        expectedTransportGroupId =
            preferred.expectedTransportGroupId ?: fallback.expectedTransportGroupId,
        expectedChunkCount = preferred.expectedChunkCount ?: fallback.expectedChunkCount,
        frameByteCount = preferred.frameByteCount ?: fallback.frameByteCount,
        senderChunksQueued = preferred.senderChunksQueued ?: fallback.senderChunksQueued,
        senderChunksWriteAttempted =
            preferred.senderChunksWriteAttempted ?: fallback.senderChunksWriteAttempted,
        senderLastLocalWriteResult =
            preferred.senderLastLocalWriteResult ?: fallback.senderLastLocalWriteResult
    )
}

private fun AutomatedDiagnosticsPhaseApplicationProbeDescriptor.phaseApplicationProbeDescriptorKey():
    AutomatedDiagnosticsPhaseApplicationProbeDescriptorKey {
    return AutomatedDiagnosticsPhaseApplicationProbeDescriptorKey(
        probeKind = probeKind,
        messageId = messageId
    )
}

fun automatedDiagnosticsPhaseApplicationProbePayloadOrNull(
    descriptors: List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor>
): String? {
    if (descriptors.isEmpty()) {
        return null
    }
    return descriptors.joinToString(automatedDiagnosticsPhaseProbeEntrySeparator) { descriptor ->
        buildList {
            add(descriptor.probeKind.name)
            add(descriptor.messageId)
            if (
                descriptor.transportStatus != null ||
                    descriptor.localBleTransportResult != null ||
                    descriptor.expectedTransportGroupId != null ||
                    descriptor.expectedChunkCount != null ||
                    descriptor.frameByteCount != null ||
                    descriptor.senderChunksQueued != null ||
                    descriptor.senderChunksWriteAttempted != null ||
                    descriptor.senderLastLocalWriteResult != null
            ) {
                add(descriptor.transportStatus ?: "")
                add(descriptor.localBleTransportResult ?: "")
                add(descriptor.expectedTransportGroupId?.toString() ?: "")
                add(descriptor.expectedChunkCount?.toString() ?: "")
                add(descriptor.frameByteCount?.toString() ?: "")
                add(descriptor.senderChunksQueued?.toString() ?: "")
                add(descriptor.senderChunksWriteAttempted?.toString() ?: "")
                add(descriptor.senderLastLocalWriteResult ?: "")
            }
        }.joinToString(automatedDiagnosticsPhaseProbeFieldSeparator)
    }
}

fun automatedDiagnosticsPhaseApplicationProbeDescriptors(
    payload: String?
): List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor> {
    val sanitizedPayload = payload?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val descriptors = mutableListOf<AutomatedDiagnosticsPhaseApplicationProbeDescriptor>()
    for (entry in sanitizedPayload.split(automatedDiagnosticsPhaseProbeEntrySeparator)) {
        val parts = entry.split(
            automatedDiagnosticsPhaseProbeFieldSeparator,
            ignoreCase = false,
            limit = 10
        )
        if (parts.size != 2 && parts.size != 4 && parts.size != 7 && parts.size != 10) {
            return emptyList()
        }
        val probeKind = runCatching {
            AutomatedDiagnosticsApplicationProbeKind.valueOf(parts[0])
        }.getOrNull() ?: return emptyList()
        val messageId = parts[1].trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val transportStatus = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
        val localBleTransportResult = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
        val expectedTransportGroupIdToken = parts.getOrNull(4)?.trim()
        val expectedTransportGroupId =
            if (expectedTransportGroupIdToken.isNullOrEmpty()) {
                null
            } else {
                expectedTransportGroupIdToken.toIntOrNull() ?: return emptyList()
            }
        val expectedChunkCountToken = parts.getOrNull(5)?.trim()
        val expectedChunkCount =
            if (expectedChunkCountToken.isNullOrEmpty()) {
                null
            } else {
                expectedChunkCountToken.toIntOrNull() ?: return emptyList()
            }
        val frameByteCountToken = parts.getOrNull(6)?.trim()
        val frameByteCount =
            if (frameByteCountToken.isNullOrEmpty()) {
                null
            } else {
                frameByteCountToken.toIntOrNull() ?: return emptyList()
            }
        val senderChunksQueuedToken = parts.getOrNull(7)?.trim()
        val senderChunksQueued =
            if (senderChunksQueuedToken.isNullOrEmpty()) {
                null
            } else {
                senderChunksQueuedToken.toIntOrNull() ?: return emptyList()
            }
        val senderChunksWriteAttemptedToken = parts.getOrNull(8)?.trim()
        val senderChunksWriteAttempted =
            if (senderChunksWriteAttemptedToken.isNullOrEmpty()) {
                null
            } else {
                senderChunksWriteAttemptedToken.toIntOrNull() ?: return emptyList()
            }
        val senderLastLocalWriteResult = parts.getOrNull(9)?.trim()?.takeIf { it.isNotEmpty() }
        descriptors += AutomatedDiagnosticsPhaseApplicationProbeDescriptor(
            probeKind = probeKind,
            messageId = messageId,
            transportStatus = transportStatus,
            localBleTransportResult = localBleTransportResult,
            expectedTransportGroupId = expectedTransportGroupId,
            expectedChunkCount = expectedChunkCount,
            frameByteCount = frameByteCount,
            senderChunksQueued = senderChunksQueued,
            senderChunksWriteAttempted = senderChunksWriteAttempted,
            senderLastLocalWriteResult = senderLastLocalWriteResult
        )
    }
    return descriptors
}

data class AutomatedDiagnosticsHybridAcceptObservation(
    val peerId: String,
    val sessionId: String,
    val publicPeerIdHint: String?,
    val createdAtMillis: Long,
    val observedAtMonotonicMillis: Long,
    val storeResult: HybridTransportControlStore.RecordResult
) {
    init {
        require(peerId.isNotBlank()) {
            "Automated diagnostics hybrid ACCEPT peerId must not be blank."
        }
        require(sessionId.isNotBlank()) {
            "Automated diagnostics hybrid ACCEPT sessionId must not be blank."
        }
        require(publicPeerIdHint?.isBlank() != true) {
            "Automated diagnostics hybrid ACCEPT publicPeerIdHint must not be blank when provided."
        }
        require(createdAtMillis >= 0L) {
            "Automated diagnostics hybrid ACCEPT createdAtMillis must be non-negative."
        }
        require(observedAtMonotonicMillis >= 0L) {
            "Automated diagnostics hybrid ACCEPT observedAtMonotonicMillis must be non-negative."
        }
    }

    val recorded: Boolean
        get() = storeResult == HybridTransportControlStore.RecordResult.Stored
}

data class AutomatedDiagnosticsHybridSocketHintObservation(
    val peerId: String,
    val sessionId: String,
    val publicPeerIdHint: String?,
    val groupOwnerAddress: String,
    val socketPort: Int,
    val createdAtMillis: Long,
    val observedAtMonotonicMillis: Long,
    val storeResult: HybridTransportControlStore.RecordResult
) {
    init {
        require(peerId.isNotBlank()) {
            "Automated diagnostics hybrid SOCKET_HINT peerId must not be blank."
        }
        require(sessionId.isNotBlank()) {
            "Automated diagnostics hybrid SOCKET_HINT sessionId must not be blank."
        }
        require(publicPeerIdHint?.isBlank() != true) {
            "Automated diagnostics hybrid SOCKET_HINT publicPeerIdHint must not be blank when provided."
        }
        require(groupOwnerAddress.isNotBlank()) {
            "Automated diagnostics hybrid SOCKET_HINT groupOwnerAddress must not be blank."
        }
        require(socketPort in 1..65535) {
            "Automated diagnostics hybrid SOCKET_HINT socketPort must be within 1..65535."
        }
        require(createdAtMillis >= 0L) {
            "Automated diagnostics hybrid SOCKET_HINT createdAtMillis must be non-negative."
        }
        require(observedAtMonotonicMillis >= 0L) {
            "Automated diagnostics hybrid SOCKET_HINT observedAtMonotonicMillis must be non-negative."
        }
    }

    val recorded: Boolean
        get() = storeResult == HybridTransportControlStore.RecordResult.Stored
}

enum class AutomatedDiagnosticsCoordinationRejectionReason(
    val statusText: String
) {
    WRONG_RUN("wrong-run"),
    WRONG_PEER("wrong-peer"),
    WRONG_SESSION("wrong-session"),
    STALE("stale"),
    UNEXPECTED_PHASE("unexpected-phase"),
    INVALID_TOKEN("invalid-token"),
    INVALID_ADDRESS("invalid-address"),
    INVALID_PAYLOAD("invalid-payload"),
    BEFORE_GROUP_READY("before-group-ready"),
    CONFLICT_LOST("conflict-lost")
}

enum class AutomatedDiagnosticsCoordinationTransition(
    val statusText: String
) {
    WAITING_FOR_ANNOUNCEMENT("WAITING_FOR_ANNOUNCEMENT"),
    ANNOUNCEMENT_SENT("ANNOUNCEMENT_SENT"),
    CONFLICT_DETECTED("CONFLICT_DETECTED"),
    LOCAL_AUTHORITY_RETAINED("LOCAL_AUTHORITY_RETAINED"),
    REMOTE_AUTHORITY_ADOPTED("REMOTE_AUTHORITY_ADOPTED"),
    JOIN_SENT("JOIN_SENT"),
    JOIN_ACCEPTED("JOIN_ACCEPTED"),
    SHARED_RUN_READY("SHARED_RUN_READY")
}

enum class AutomatedDiagnosticsCoordinationConflictOutcome(
    val statusText: String
) {
    SAME_PAIR_PROVISIONAL_CONFLICT("SAME_PAIR_PROVISIONAL_CONFLICT"),
    LOCAL_PROVISIONAL_WON("LOCAL_PROVISIONAL_WON"),
    REMOTE_PROVISIONAL_WON_AND_ADOPTED("REMOTE_PROVISIONAL_WON_AND_ADOPTED")
}

data class AutomatedDiagnosticsCoordinationCounters(
    val messagesReceived: Int = 0,
    val messagesAccepted: Int = 0,
    val wrongRunRejected: Int = 0,
    val wrongPeerRejected: Int = 0,
    val wrongSessionRejected: Int = 0,
    val staleRejected: Int = 0,
    val invalidPayloadRejected: Int = 0,
    val lastRejectedReason: AutomatedDiagnosticsCoordinationRejectionReason? = null
) {
    init {
        require(messagesReceived >= 0) {
            "Automated diagnostics coordination messagesReceived must be non-negative."
        }
        require(messagesAccepted >= 0) {
            "Automated diagnostics coordination messagesAccepted must be non-negative."
        }
        require(wrongRunRejected >= 0) {
            "Automated diagnostics coordination wrongRunRejected must be non-negative."
        }
        require(wrongPeerRejected >= 0) {
            "Automated diagnostics coordination wrongPeerRejected must be non-negative."
        }
        require(wrongSessionRejected >= 0) {
            "Automated diagnostics coordination wrongSessionRejected must be non-negative."
        }
        require(staleRejected >= 0) {
            "Automated diagnostics coordination staleRejected must be non-negative."
        }
        require(invalidPayloadRejected >= 0) {
            "Automated diagnostics coordination invalidPayloadRejected must be non-negative."
        }
    }

    fun recordAccepted(): AutomatedDiagnosticsCoordinationCounters {
        return copy(
            messagesReceived = messagesReceived + 1,
            messagesAccepted = messagesAccepted + 1
        )
    }

    fun recordRejected(
        reason: AutomatedDiagnosticsCoordinationRejectionReason
    ): AutomatedDiagnosticsCoordinationCounters {
        return when (reason) {
            AutomatedDiagnosticsCoordinationRejectionReason.WRONG_RUN -> copy(
                messagesReceived = messagesReceived + 1,
                wrongRunRejected = wrongRunRejected + 1,
                lastRejectedReason = reason
            )

            AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER -> copy(
                messagesReceived = messagesReceived + 1,
                wrongPeerRejected = wrongPeerRejected + 1,
                lastRejectedReason = reason
            )

            AutomatedDiagnosticsCoordinationRejectionReason.WRONG_SESSION -> copy(
                messagesReceived = messagesReceived + 1,
                wrongSessionRejected = wrongSessionRejected + 1,
                lastRejectedReason = reason
            )

            AutomatedDiagnosticsCoordinationRejectionReason.STALE -> copy(
                messagesReceived = messagesReceived + 1,
                staleRejected = staleRejected + 1,
                lastRejectedReason = reason
            )

            AutomatedDiagnosticsCoordinationRejectionReason.UNEXPECTED_PHASE,
            AutomatedDiagnosticsCoordinationRejectionReason.INVALID_TOKEN,
            AutomatedDiagnosticsCoordinationRejectionReason.INVALID_ADDRESS,
            AutomatedDiagnosticsCoordinationRejectionReason.INVALID_PAYLOAD,
            AutomatedDiagnosticsCoordinationRejectionReason.BEFORE_GROUP_READY,
            AutomatedDiagnosticsCoordinationRejectionReason.CONFLICT_LOST -> copy(
                messagesReceived = messagesReceived + 1,
                invalidPayloadRejected = invalidPayloadRejected + 1,
                lastRejectedReason = reason
            )
        }
    }
}

sealed interface AutomatedDiagnosticsRunAnnouncementSendResult {
    data class Sent(
        val sharedRun: AutomatedDiagnosticsSharedRun
    ) : AutomatedDiagnosticsRunAnnouncementSendResult

    data object NoActivePeer : AutomatedDiagnosticsRunAnnouncementSendResult

    data object WriterUnavailable : AutomatedDiagnosticsRunAnnouncementSendResult

    data class InvalidAnnouncement(
        val reason: String
    ) : AutomatedDiagnosticsRunAnnouncementSendResult {
        init {
            require(reason.isNotBlank()) {
                "Automated diagnostics invalid announcement reason must not be blank."
            }
        }
    }

    data class SendFailed(
        val reason: String
    ) : AutomatedDiagnosticsRunAnnouncementSendResult {
        init {
            require(reason.isNotBlank()) {
                "Automated diagnostics announcement send failure reason must not be blank."
            }
        }
    }
}

sealed interface AutomatedDiagnosticsParticipantJoinSendResult {
    data class Sent(
        val sharedRun: AutomatedDiagnosticsSharedRun
    ) : AutomatedDiagnosticsParticipantJoinSendResult

    data object NoActivePeer : AutomatedDiagnosticsParticipantJoinSendResult

    data object WriterUnavailable : AutomatedDiagnosticsParticipantJoinSendResult

    data class InvalidJoin(
        val reason: String
    ) : AutomatedDiagnosticsParticipantJoinSendResult {
        init {
            require(reason.isNotBlank()) {
                "Automated diagnostics invalid participant-join reason must not be blank."
            }
        }
    }

    data class SendFailed(
        val reason: String
    ) : AutomatedDiagnosticsParticipantJoinSendResult {
        init {
            require(reason.isNotBlank()) {
                "Automated diagnostics participant-join send failure reason must not be blank."
            }
        }
    }
}

sealed interface AutomatedDiagnosticsServerReadySendResult {
    data class Sent(
        val signal: AutomatedDiagnosticsServerReadySignal
    ) : AutomatedDiagnosticsServerReadySendResult

    data object NoActivePeer : AutomatedDiagnosticsServerReadySendResult

    data object WriterUnavailable : AutomatedDiagnosticsServerReadySendResult

    data class InvalidSignal(
        val reason: String
    ) : AutomatedDiagnosticsServerReadySendResult {
        init {
            require(reason.isNotBlank()) {
                "Automated diagnostics invalid server-ready reason must not be blank."
            }
        }
    }

    data class SendFailed(
        val reason: String
    ) : AutomatedDiagnosticsServerReadySendResult {
        init {
            require(reason.isNotBlank()) {
                "Automated diagnostics server-ready send failure reason must not be blank."
            }
        }
    }
}

sealed interface AutomatedDiagnosticsPhaseStateSendResult {
    data class Sent(
        val signal: AutomatedDiagnosticsPhaseSignal
    ) : AutomatedDiagnosticsPhaseStateSendResult

    data object NoActivePeer : AutomatedDiagnosticsPhaseStateSendResult

    data object WriterUnavailable : AutomatedDiagnosticsPhaseStateSendResult

    data class InvalidSignal(
        val reason: String
    ) : AutomatedDiagnosticsPhaseStateSendResult {
        init {
            require(reason.isNotBlank()) {
                "Automated diagnostics invalid phase-state reason must not be blank."
            }
        }
    }

    data class SendFailed(
        val reason: String
    ) : AutomatedDiagnosticsPhaseStateSendResult {
        init {
            require(reason.isNotBlank()) {
                "Automated diagnostics phase-state send failure reason must not be blank."
            }
        }
    }
}

sealed interface AutomatedDiagnosticsWifiDirectPeerReadySendResult {
    data class Sent(
        val signal: AutomatedDiagnosticsWifiDirectPeerReadySignal
    ) : AutomatedDiagnosticsWifiDirectPeerReadySendResult

    data object NoActivePeer : AutomatedDiagnosticsWifiDirectPeerReadySendResult

    data object WriterUnavailable : AutomatedDiagnosticsWifiDirectPeerReadySendResult

    data class InvalidSignal(
        val reason: String
    ) : AutomatedDiagnosticsWifiDirectPeerReadySendResult {
        init {
            require(reason.isNotBlank()) {
                "Automated diagnostics invalid Wi-Fi peer-ready reason must not be blank."
            }
        }
    }

    data class SendFailed(
        val reason: String
    ) : AutomatedDiagnosticsWifiDirectPeerReadySendResult {
        init {
            require(reason.isNotBlank()) {
                "Automated diagnostics Wi-Fi peer-ready send failure reason must not be blank."
            }
        }
    }
}

fun automatedDiagnosticsRunAnnouncementStatusText(
    announcement: AutomatedDiagnosticsRunAnnouncement?
): String? {
    return announcement?.let {
        "Run announce: run=${it.sharedRun.runId} coordinator=${it.sharedRun.coordinatorPeerId} participant=${it.sharedRun.participantPeerId}"
    }
}

fun automatedDiagnosticsParticipantJoinStatusText(
    join: AutomatedDiagnosticsParticipantJoin?
): String? {
    return join?.let {
        "Participant join: run=${it.sharedRun.runId} participant=${it.sharedRun.participantPeerId} coordinator=${it.sharedRun.coordinatorPeerId}"
    }
}

fun automatedDiagnosticsServerReadyStatusText(
    signal: AutomatedDiagnosticsServerReadySignal?
): String? {
    return signal?.let {
        "Server-ready signal: run=${it.sharedRun.runId} peer=${it.peerId} client=${it.expectedClientPeerId} address=${it.groupOwnerAddress} port=${it.socketPort} token=${it.serverToken}"
    }
}

fun automatedDiagnosticsWifiDirectPeerReadyStatusText(
    signal: AutomatedDiagnosticsWifiDirectPeerReadySignal?
): String? {
    return signal?.let {
        "Wi-Fi peer-ready: run=${it.sharedRun.runId} sender=${it.peerId} " +
            "remote=${it.expectedRemotePeerId} token=${it.wifiDirectCorrelationTokenFingerprint()} " +
            "name=${it.wifiDirectDeviceName ?: "none"}"
    }
}

fun automatedDiagnosticsPhaseStateStatusText(
    signal: AutomatedDiagnosticsPhaseSignal?
): String? {
    return signal?.let {
        "Phase state: run=${it.sharedRun.runId} peer=${it.peerId} remote=${it.expectedRemotePeerId} " +
            "step=${it.stepId.stepNumber} state=${it.phaseState.statusText} attempt=${it.attemptNumber}"
    }
}

fun automatedDiagnosticsRunAnnouncementSendStatusText(
    result: AutomatedDiagnosticsRunAnnouncementSendResult
): String {
    return when (result) {
        is AutomatedDiagnosticsRunAnnouncementSendResult.Sent ->
            "Run announcement sent: run=${result.sharedRun.runId} coordinator=${result.sharedRun.coordinatorPeerId} participant=${result.sharedRun.participantPeerId}"

        AutomatedDiagnosticsRunAnnouncementSendResult.NoActivePeer ->
            "Run announcement unavailable: no active BLE peer."

        AutomatedDiagnosticsRunAnnouncementSendResult.WriterUnavailable ->
            "Run announcement unavailable: BLE writer unavailable."

        is AutomatedDiagnosticsRunAnnouncementSendResult.InvalidAnnouncement ->
            "Run announcement invalid: ${result.reason}"

        is AutomatedDiagnosticsRunAnnouncementSendResult.SendFailed ->
            "Run announcement failed: ${result.reason}"
    }
}

fun automatedDiagnosticsParticipantJoinSendStatusText(
    result: AutomatedDiagnosticsParticipantJoinSendResult
): String {
    return when (result) {
        is AutomatedDiagnosticsParticipantJoinSendResult.Sent ->
            "Participant join sent: run=${result.sharedRun.runId} participant=${result.sharedRun.participantPeerId}"

        AutomatedDiagnosticsParticipantJoinSendResult.NoActivePeer ->
            "Participant join unavailable: no active BLE peer."

        AutomatedDiagnosticsParticipantJoinSendResult.WriterUnavailable ->
            "Participant join unavailable: BLE writer unavailable."

        is AutomatedDiagnosticsParticipantJoinSendResult.InvalidJoin ->
            "Participant join invalid: ${result.reason}"

        is AutomatedDiagnosticsParticipantJoinSendResult.SendFailed ->
            "Participant join failed: ${result.reason}"
    }
}

fun automatedDiagnosticsWifiDirectPeerReadySendStatusText(
    result: AutomatedDiagnosticsWifiDirectPeerReadySendResult
): String {
    return when (result) {
        is AutomatedDiagnosticsWifiDirectPeerReadySendResult.Sent ->
            "Wi-Fi peer-ready sent: run=${result.signal.sharedRun.runId} " +
                "sender=${result.signal.peerId} " +
                "remote=${result.signal.expectedRemotePeerId} " +
                "token=${result.signal.wifiDirectCorrelationTokenFingerprint()}"

        AutomatedDiagnosticsWifiDirectPeerReadySendResult.NoActivePeer ->
            "Wi-Fi peer-ready unavailable: no active BLE peer."

        AutomatedDiagnosticsWifiDirectPeerReadySendResult.WriterUnavailable ->
            "Wi-Fi peer-ready unavailable: BLE writer unavailable."

        is AutomatedDiagnosticsWifiDirectPeerReadySendResult.InvalidSignal ->
            "Wi-Fi peer-ready invalid: ${result.reason}"

        is AutomatedDiagnosticsWifiDirectPeerReadySendResult.SendFailed ->
            "Wi-Fi peer-ready failed: ${result.reason}"
    }
}

fun AutomatedDiagnosticsWifiDirectPeerReadySignal.wifiDirectCorrelationTokenFingerprint(): String {
    return automatedDiagnosticsCorrelationTokenFingerprint(wifiDirectCorrelationToken)
}

fun automatedDiagnosticsCorrelationTokenFingerprint(
    token: String
): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .take(4)
        .joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
}

fun isAutomatedDiagnosticsCorrelationTokenValid(
    token: String
): Boolean {
    return token.length in
        automatedDiagnosticsCorrelationTokenMinLength..
        automatedDiagnosticsCorrelationTokenMaxLength &&
        automatedDiagnosticsCorrelationTokenPattern.matches(token)
}

fun automatedDiagnosticsServerReadySendStatusText(
    result: AutomatedDiagnosticsServerReadySendResult
): String {
    return when (result) {
        is AutomatedDiagnosticsServerReadySendResult.Sent ->
            "Server-ready signal sent: run=${result.signal.sharedRun.runId} peer=${result.signal.peerId} client=${result.signal.expectedClientPeerId} address=${result.signal.groupOwnerAddress} port=${result.signal.socketPort} token=${result.signal.serverToken}"

        AutomatedDiagnosticsServerReadySendResult.NoActivePeer ->
            "Server-ready signal unavailable: no active BLE peer."

        AutomatedDiagnosticsServerReadySendResult.WriterUnavailable ->
            "Server-ready signal unavailable: BLE writer unavailable."

        is AutomatedDiagnosticsServerReadySendResult.InvalidSignal ->
            "Server-ready signal invalid: ${result.reason}"

        is AutomatedDiagnosticsServerReadySendResult.SendFailed ->
            "Server-ready signal failed: ${result.reason}"
    }
}

fun automatedDiagnosticsPhaseStateSendStatusText(
    result: AutomatedDiagnosticsPhaseStateSendResult
): String {
    return when (result) {
        is AutomatedDiagnosticsPhaseStateSendResult.Sent ->
            "Phase state sent: ${automatedDiagnosticsPhaseStateStatusText(result.signal)}"

        AutomatedDiagnosticsPhaseStateSendResult.NoActivePeer ->
            "Phase state unavailable: no active BLE peer."

        AutomatedDiagnosticsPhaseStateSendResult.WriterUnavailable ->
            "Phase state unavailable: BLE writer unavailable."

        is AutomatedDiagnosticsPhaseStateSendResult.InvalidSignal ->
            "Phase state invalid: ${result.reason}"

        is AutomatedDiagnosticsPhaseStateSendResult.SendFailed ->
            "Phase state failed: ${result.reason}"
    }
}
