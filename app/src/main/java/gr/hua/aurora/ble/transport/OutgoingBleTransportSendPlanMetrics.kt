package gr.hua.aurora.ble.transport

data class OutgoingBleTransportSendPlanMetrics(
    val encodedPayloadByteCount: Int,
    val chunkCount: Int,
    val chunkPayloadSizes: List<Int>,
    val frameEncodedSizes: List<Int>
) {
    init {
        require(encodedPayloadByteCount >= 0) {
            "Outgoing BLE transport send plan metrics encodedPayloadByteCount must be non-negative."
        }
        require(chunkCount >= 0) {
            "Outgoing BLE transport send plan metrics chunkCount must be non-negative."
        }
        require(chunkPayloadSizes.size == chunkCount) {
            "Outgoing BLE transport send plan metrics chunkPayloadSizes size must match chunkCount."
        }
        require(frameEncodedSizes.size == chunkCount) {
            "Outgoing BLE transport send plan metrics frameEncodedSizes size must match chunkCount."
        }
        require(chunkPayloadSizes.none { it <= 0 }) {
            "Outgoing BLE transport send plan metrics chunk payload sizes must be positive."
        }
        require(frameEncodedSizes.none { it <= 0 }) {
            "Outgoing BLE transport send plan metrics encoded frame sizes must be positive."
        }
    }
}

fun OutgoingBleTransportSendPlan.metrics(): OutgoingBleTransportSendPlanMetrics {
    val frames = framesInSendOrder()
    val chunks = frames.map { frame ->
        requireNotNull(BleGattTransportChunk.parse(frame)) {
            "Outgoing BLE transport send plan frame does not decode to a transport chunk."
        }
    }
    return OutgoingBleTransportSendPlanMetrics(
        encodedPayloadByteCount = chunks.sumOf { chunk -> chunk.payloadToByteArray().size },
        chunkCount = frames.size,
        chunkPayloadSizes = chunks.map { chunk -> chunk.payloadToByteArray().size },
        frameEncodedSizes = frames.map { frame -> frame.toByteArray().size }
    )
}
