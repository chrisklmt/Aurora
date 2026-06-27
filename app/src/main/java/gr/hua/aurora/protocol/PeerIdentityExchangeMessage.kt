package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.Sec1PublicKeyEncoding

class PeerIdentityExchangeMessage(
    val peerId: String,
    publicAgreementKeyBytes: ByteArray,
    val createdAtMillis: Long,
    val privateChatProposalId: String? = null
) {
    private val storedPublicAgreementKeyBytes = publicAgreementKeyBytes.copyOf()

    init {
        require(peerId.isNotBlank()) {
            "Peer identity exchange peerId must not be blank."
        }
        require(createdAtMillis >= 0L) {
            "Peer identity exchange createdAtMillis must be non-negative."
        }
        require(privateChatProposalId?.isNotBlank() != false) {
            "Peer identity exchange privateChatProposalId must not be blank when present."
        }
        Sec1PublicKeyEncoding.decodeUncompressed(storedPublicAgreementKeyBytes)
    }

    fun publicAgreementKeyBytes(): ByteArray {
        return storedPublicAgreementKeyBytes.copyOf()
    }

    fun toMessageFrame(
        frameId: String = defaultFrameId()
    ): MessageFrame {
        require(frameId.isNotBlank()) {
            "Peer identity exchange frameId must not be blank."
        }

        return MessageFrame(
            id = frameId,
            type = MessageFrameType.IDENTITY_EXCHANGE,
            senderId = peerId,
            createdAtMillis = createdAtMillis,
            payload = PeerIdentityExchangeCodec.encode(this)
        )
    }

    private fun defaultFrameId(): String {
        return "identity:$peerId:$createdAtMillis"
    }

    companion object {
        fun fromMessageFrame(
            frame: MessageFrame
        ): PeerIdentityExchangeMessage {
            require(frame.type == MessageFrameType.IDENTITY_EXCHANGE) {
                "Message frame type must be IDENTITY_EXCHANGE."
            }

            val message = PeerIdentityExchangeCodec.decode(frame.payload)
            require(frame.senderId == message.peerId) {
                "Identity exchange frame senderId must match the encoded peerId."
            }
            require(frame.createdAtMillis == message.createdAtMillis) {
                "Identity exchange frame createdAtMillis must match the encoded timestamp."
            }

            return message
        }
    }
}
