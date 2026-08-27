package gr.hua.aurora.ble.transport

object OutgoingBleTransportSendPlanBuilder {
    fun build(
        messageId: String,
        targetPeerId: String?,
        encryptedEnvelopeBytes: ByteArray,
        sourceCreatedAtMillis: Long? = null
    ): OutgoingBleTransportSendPlan {
        require(messageId.isNotBlank()) {
            "Outgoing BLE transport send plan messageId must not be blank."
        }
        require(targetPeerId == null || targetPeerId.isNotBlank()) {
            "Outgoing BLE transport send plan targetPeerId must not be blank when present."
        }

        val groupId = deriveGroupId(messageId, targetPeerId)
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = encryptedEnvelopeBytes,
            groupId = groupId
        )

        return OutgoingBleTransportSendPlan.create(
            messageId = messageId,
            targetPeerId = targetPeerId,
            groupId = groupId,
            sourceCreatedAtMillis = sourceCreatedAtMillis,
            frames = frames
        )
    }

    internal fun deriveGroupId(
        messageId: String,
        targetPeerId: String?
    ): Int {
        val seed = if (targetPeerId == null) {
            messageId
        } else {
            "$messageId|$targetPeerId"
        }

        return seed.fold(0) { hash, character ->
            ((hash * 31) + character.code) and 0xFFFF
        }
    }
}
