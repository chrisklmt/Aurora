package gr.hua.aurora.ble.transport

object BleGattTransportFrameReassembler {
    fun reassemble(frames: List<BleGattTransportFrame>): ByteArray {
        require(frames.isNotEmpty()) {
            "Transport frames must not be empty."
        }

        val chunks = frames.mapIndexed { index, frame ->
            BleGattTransportChunk.parse(frame)
                ?: throw IllegalArgumentException("Invalid transport chunk at frame index $index.")
        }
        val expectedGroupId = chunks.first().groupId
        val expectedTotalChunks = chunks.first().totalChunks
        val chunksByIndex = LinkedHashMap<Int, BleGattTransportChunk>(chunks.size)

        chunks.forEach { chunk ->
            require(chunk.groupId == expectedGroupId) {
                "Mismatched transport chunk groupId: ${chunk.groupId}."
            }
            require(chunk.totalChunks == expectedTotalChunks) {
                "Mismatched transport chunk totalChunks: ${chunk.totalChunks}."
            }
            require(chunksByIndex.put(chunk.chunkIndex, chunk) == null) {
                "Duplicate transport chunk index: ${chunk.chunkIndex}."
            }
        }

        require(chunksByIndex.size == expectedTotalChunks) {
            "Missing transport chunks for groupId $expectedGroupId."
        }

        return buildList(expectedTotalChunks) {
            for (chunkIndex in 0 until expectedTotalChunks) {
                add(
                    requireNotNull(chunksByIndex[chunkIndex]) {
                        "Missing transport chunk index: $chunkIndex."
                    }
                )
            }
        }.fold(ByteArray(0)) { bytes, chunk ->
            bytes + chunk.payloadToByteArray()
        }
    }
}
