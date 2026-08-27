package gr.hua.aurora.transport.hybrid

data class HybridTransportControlMessage(
    val protocolVersion: Int = HybridTransportControlCodec.currentProtocolVersion,
    val messageType: MessageType,
    val sessionId: String,
    val publicPeerIdHint: String? = null,
    val relatedPeerIdHint: String? = null,
    val senderPeerIdHint: String? = null,
    val expectedPeerIdHint: String? = null,
    val wifiDirectCorrelationToken: String? = null,
    val wifiDirectDeviceAddress: String? = null,
    val wifiDirectDeviceName: String? = null,
    val groupOwnerAddress: String? = null,
    val socketPort: Int? = null,
    val diagnosticsStepNumber: Int? = null,
    val diagnosticsPhaseState: String? = null,
    val diagnosticsAttemptNumber: Int? = null,
    val diagnosticsApplicationProbePayload: String? = null,
    val createdAtMillis: Long,
    val associatedSessionId: String? = null,
    val expiresAtMillis: Long? = null,
    val generationToken: Long? = null,
    val capabilityFlags: Set<CapabilityFlag> = emptySet()
) {
    init {
        require(protocolVersion >= 1) {
            "Hybrid transport protocol version must be positive."
        }
        require(sessionId.isNotBlank()) {
            "Hybrid transport session id must not be blank."
        }
        require(publicPeerIdHint?.isBlank() != true) {
            "Hybrid transport public peer hint must not be blank when provided."
        }
        require(relatedPeerIdHint?.isBlank() != true) {
            "Hybrid transport related peer hint must not be blank when provided."
        }
        require(senderPeerIdHint?.isBlank() != true) {
            "Hybrid transport sender peer hint must not be blank when provided."
        }
        require(expectedPeerIdHint?.isBlank() != true) {
            "Hybrid transport expected peer hint must not be blank when provided."
        }
        require(wifiDirectCorrelationToken?.isBlank() != true) {
            "Hybrid transport Wi-Fi Direct correlation token must not be blank when provided."
        }
        require(wifiDirectDeviceAddress?.isBlank() != true) {
            "Hybrid transport Wi-Fi Direct device address must not be blank when provided."
        }
        require(wifiDirectDeviceName?.isBlank() != true) {
            "Hybrid transport Wi-Fi Direct device name must not be blank when provided."
        }
        require(groupOwnerAddress?.isBlank() != true) {
            "Hybrid transport group owner address must not be blank when provided."
        }
        require(socketPort == null || socketPort in 1..65535) {
            "Hybrid transport socket port must be within 1..65535 when provided."
        }
        require(diagnosticsStepNumber == null || diagnosticsStepNumber > 0) {
            "Hybrid transport diagnostics step number must be positive when provided."
        }
        require(diagnosticsPhaseState?.isBlank() != true) {
            "Hybrid transport diagnostics phase state must not be blank when provided."
        }
        require(diagnosticsAttemptNumber == null || diagnosticsAttemptNumber > 0) {
            "Hybrid transport diagnostics attempt number must be positive when provided."
        }
        require(diagnosticsApplicationProbePayload?.isBlank() != true) {
            "Hybrid transport diagnostics application probe payload must not be blank when provided."
        }
        require(associatedSessionId?.isBlank() != true) {
            "Hybrid transport associated session id must not be blank when provided."
        }
        require(createdAtMillis >= 0L) {
            "Hybrid transport createdAtMillis must be non-negative."
        }
        require(expiresAtMillis == null || expiresAtMillis >= createdAtMillis) {
            "Hybrid transport expiresAtMillis must be at least createdAtMillis when provided."
        }
        require(generationToken == null || generationToken >= 0L) {
            "Hybrid transport generation token must be non-negative when provided."
        }
    }

    enum class MessageType {
        WIFI_DIRECT_OFFER,
        WIFI_DIRECT_ACCEPT,
        WIFI_DIRECT_SOCKET_HINT,
        AUTOMATED_DIAGNOSTICS_RUN_ANNOUNCE,
        AUTOMATED_DIAGNOSTICS_PARTICIPANT_JOIN,
        AUTOMATED_DIAGNOSTICS_PHASE_READY,
        AUTOMATED_DIAGNOSTICS_SERVER_READY,
        AUTOMATED_DIAGNOSTICS_RUN_CANCEL,
        AUTOMATED_DIAGNOSTICS_RUN_COMPLETE
    }

    enum class CapabilityFlag {
        WIFI_DIRECT_BOOTSTRAP,
        WIFI_DIRECT_SOCKET_HINT,
        BLE_FALLBACK
    }
}
