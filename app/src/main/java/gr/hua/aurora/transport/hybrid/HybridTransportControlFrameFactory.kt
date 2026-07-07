package gr.hua.aurora.transport.hybrid

import gr.hua.aurora.protocol.MessageFrame
import gr.hua.aurora.protocol.MessageFrameType

object HybridTransportControlFrameFactory {
    fun create(
        message: HybridTransportControlMessage,
        frameId: String,
        senderId: String,
        recipientId: String? = null,
        ttl: Int = 1
    ): MessageFrame {
        require(frameId.isNotBlank()) {
            "Hybrid transport control frame id must not be blank."
        }
        require(senderId.isNotBlank()) {
            "Hybrid transport control sender id must not be blank."
        }
        require(recipientId?.isBlank() != true) {
            "Hybrid transport control recipient id must not be blank when provided."
        }
        require(ttl >= 1) {
            "Hybrid transport control frame ttl must be at least 1."
        }

        return MessageFrame(
            id = frameId,
            type = MessageFrameType.HYBRID_TRANSPORT_CONTROL,
            senderId = senderId,
            recipientId = recipientId,
            createdAtMillis = message.createdAtMillis,
            ttl = ttl,
            payload = HybridTransportControlCodec.encode(message)
        )
    }

    fun parse(
        frame: MessageFrame
    ): HybridTransportControlMessage {
        require(frame.type == MessageFrameType.HYBRID_TRANSPORT_CONTROL) {
            "Message frame type must be HYBRID_TRANSPORT_CONTROL."
        }

        val message = HybridTransportControlCodec.decode(frame.payload)
        require(frame.createdAtMillis == message.createdAtMillis) {
            "Hybrid transport control frame createdAtMillis must match the encoded timestamp."
        }
        return message
    }

    fun parseOrNull(
        frame: MessageFrame
    ): HybridTransportControlMessage? {
        if (frame.type != MessageFrameType.HYBRID_TRANSPORT_CONTROL) {
            return null
        }

        val message = HybridTransportControlCodec.decodeOrNull(frame.payload)
            ?: return null
        return message.takeIf { it.createdAtMillis == frame.createdAtMillis }
    }
}
