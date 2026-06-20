package gr.hua.aurora.ble.transport

class OutgoingBleTransportSendPlan private constructor(
    val messageId: String,
    val targetPeerId: String?,
    val groupId: Int,
    val sourceCreatedAtMillis: Long?,
    frames: List<BleGattTransportFrame>
) {
    private val frames = frames.toList()

    init {
        require(messageId.isNotBlank()) {
            "Outgoing BLE transport send plan messageId must not be blank."
        }
        require(targetPeerId == null || targetPeerId.isNotBlank()) {
            "Outgoing BLE transport send plan targetPeerId must not be blank when present."
        }
        require(groupId in 0..MAX_UNSIGNED_SHORT) {
            "Outgoing BLE transport send plan groupId must be between 0 and $MAX_UNSIGNED_SHORT."
        }
        require(sourceCreatedAtMillis == null || sourceCreatedAtMillis >= 0L) {
            "Outgoing BLE transport send plan sourceCreatedAtMillis must be non-negative when present."
        }
        require(this.frames.isNotEmpty()) {
            "Outgoing BLE transport send plan must include at least one frame."
        }
    }

    fun framesInSendOrder(): List<BleGattTransportFrame> {
        return frames.toList()
    }

    companion object {
        private const val MAX_UNSIGNED_SHORT: Int = 0xFFFF

        fun create(
            messageId: String,
            targetPeerId: String?,
            groupId: Int,
            sourceCreatedAtMillis: Long?,
            frames: List<BleGattTransportFrame>
        ): OutgoingBleTransportSendPlan {
            return OutgoingBleTransportSendPlan(
                messageId = messageId,
                targetPeerId = targetPeerId,
                groupId = groupId,
                sourceCreatedAtMillis = sourceCreatedAtMillis,
                frames = frames
            )
        }
    }
}
