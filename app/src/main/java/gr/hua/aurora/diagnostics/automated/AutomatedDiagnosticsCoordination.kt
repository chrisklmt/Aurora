package gr.hua.aurora.diagnostics.automated

import java.security.MessageDigest

private const val automatedDiagnosticsCorrelationTokenMinLength = 16
private const val automatedDiagnosticsCorrelationTokenMaxLength = 128
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

enum class AutomatedDiagnosticsCoordinationRejectionReason(
    val statusText: String
) {
    WRONG_RUN("wrong-run"),
    WRONG_PEER("wrong-peer"),
    WRONG_SESSION("wrong-session"),
    STALE("stale"),
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
