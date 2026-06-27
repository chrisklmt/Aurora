package gr.hua.aurora.ble.transport

object BleGattTransportFrameChunker {
    const val MAX_SUPPORTED_FRAMES_PER_GROUP: Int = 128

    fun chunk(
        encodedEnvelopeBytes: ByteArray,
        groupId: Int
    ): List<BleGattTransportFrame> {
        require(encodedEnvelopeBytes.isNotEmpty()) {
            "Encoded envelope bytes must not be empty."
        }

        val totalChunks = (encodedEnvelopeBytes.size + BleGattTransportChunk.MAX_PAYLOAD_SIZE - 1) /
            BleGattTransportChunk.MAX_PAYLOAD_SIZE
        require(totalChunks <= MAX_SUPPORTED_FRAMES_PER_GROUP) {
            "Encoded payload requires $totalChunks transport frames, which exceeds the supported limit of $MAX_SUPPORTED_FRAMES_PER_GROUP."
        }

        return (0 until totalChunks).map { chunkIndex ->
            val start = chunkIndex * BleGattTransportChunk.MAX_PAYLOAD_SIZE
            val endExclusive = minOf(
                start + BleGattTransportChunk.MAX_PAYLOAD_SIZE,
                encodedEnvelopeBytes.size
            )
            val chunk = checkNotNull(
                BleGattTransportChunk.create(
                    groupId = groupId,
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                    payload = encodedEnvelopeBytes.copyOfRange(start, endExclusive)
                )
            )

            checkNotNull(chunk.toFrame())
        }
    }
}
