package gr.hua.aurora.ble.transport

data class BleTransportLocalSendTrace(
    val messageId: String,
    val targetPeerId: String?,
    val groupId: Int,
    val encodedPayloadByteCount: Int,
    val chunkCount: Int,
    val chunkPayloadSizes: List<Int>,
    val frameEncodedSizes: List<Int>,
    val chunksQueued: Int,
    val chunksWriteAttempted: Int,
    val lastLocalWriteResult: String
) {
    init {
        require(messageId.isNotBlank()) {
            "BLE transport local send trace messageId must not be blank."
        }
        require(targetPeerId?.isBlank() != true) {
            "BLE transport local send trace targetPeerId must not be blank when present."
        }
        require(groupId in 0..0xFFFF) {
            "BLE transport local send trace groupId must be between 0 and 65535."
        }
        require(encodedPayloadByteCount >= 0) {
            "BLE transport local send trace encodedPayloadByteCount must be non-negative."
        }
        require(chunkCount >= 0) {
            "BLE transport local send trace chunkCount must be non-negative."
        }
        require(chunkPayloadSizes.size == chunkCount) {
            "BLE transport local send trace chunkPayloadSizes size must match chunkCount."
        }
        require(frameEncodedSizes.size == chunkCount) {
            "BLE transport local send trace frameEncodedSizes size must match chunkCount."
        }
        require(chunksQueued in 0..chunkCount) {
            "BLE transport local send trace chunksQueued must be within 0..chunkCount."
        }
        require(chunksWriteAttempted in 0..chunkCount) {
            "BLE transport local send trace chunksWriteAttempted must be within 0..chunkCount."
        }
        require(lastLocalWriteResult.isNotBlank()) {
            "BLE transport local send trace lastLocalWriteResult must not be blank."
        }
    }
}
