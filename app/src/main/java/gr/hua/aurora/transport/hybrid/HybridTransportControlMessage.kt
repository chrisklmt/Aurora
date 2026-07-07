package gr.hua.aurora.transport.hybrid

data class HybridTransportControlMessage(
    val protocolVersion: Int = HybridTransportControlCodec.currentProtocolVersion,
    val messageType: MessageType,
    val sessionId: String,
    val publicPeerIdHint: String? = null,
    val groupOwnerAddress: String? = null,
    val socketPort: Int? = null,
    val createdAtMillis: Long,
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
        require(groupOwnerAddress?.isBlank() != true) {
            "Hybrid transport group owner address must not be blank when provided."
        }
        require(socketPort == null || socketPort in 1..65535) {
            "Hybrid transport socket port must be within 1..65535 when provided."
        }
        require(createdAtMillis >= 0L) {
            "Hybrid transport createdAtMillis must be non-negative."
        }
    }

    enum class MessageType {
        WIFI_DIRECT_OFFER,
        WIFI_DIRECT_ACCEPT,
        WIFI_DIRECT_SOCKET_HINT
    }

    enum class CapabilityFlag {
        WIFI_DIRECT_BOOTSTRAP,
        WIFI_DIRECT_SOCKET_HINT,
        BLE_FALLBACK
    }
}
