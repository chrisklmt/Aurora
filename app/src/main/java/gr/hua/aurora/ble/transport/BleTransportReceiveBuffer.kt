package gr.hua.aurora.ble.transport

class BleTransportReceiveBuffer(
    private val maxGroups: Int = DEFAULT_MAX_GROUPS,
    private val maxFramesPerGroup: Int = DEFAULT_MAX_FRAMES_PER_GROUP
) {
    private val groups = LinkedHashMap<Int, GroupState>()

    init {
        require(maxGroups > 0) {
            "Transport receive buffer maxGroups must be positive."
        }
        require(maxFramesPerGroup > 0) {
            "Transport receive buffer maxFramesPerGroup must be positive."
        }
    }

    fun buffer(
        frame: BleTransportIncomingFrame
    ): BufferResult {
        val chunk = BleGattTransportChunk.parse(frame.frame)
            ?: return BufferResult.InvalidChunk(
                reason = "Transport frame does not contain a valid chunk body."
            )
        if (chunk.totalChunks > maxFramesPerGroup) {
            return BufferResult.BufferOverflow(
                reason = "Transport chunk group ${chunk.groupId} has ${chunk.totalChunks} chunks and exceeds the receive frame limit of $maxFramesPerGroup."
            )
        }

        val existingGroup = groups[chunk.groupId]
        if (existingGroup == null && groups.size >= maxGroups) {
            return BufferResult.BufferOverflow(
                reason = "Transport receive buffer already tracks $maxGroups active groups."
            )
        }

        val group = existingGroup ?: GroupState(
            expectedChunks = chunk.totalChunks,
            sourceDeviceAddress = frame.sanitizedSourceDeviceAddress
        ).also { groups[chunk.groupId] = it }

        if (group.expectedChunks != chunk.totalChunks) {
            groups.remove(chunk.groupId)
            return BufferResult.InvalidChunk(
                reason = "Transport chunk group ${chunk.groupId} changed totalChunks from ${group.expectedChunks} to ${chunk.totalChunks}."
            )
        }
        if (group.sourceDeviceAddress != frame.sanitizedSourceDeviceAddress) {
            groups.remove(chunk.groupId)
            return BufferResult.InvalidChunk(
                reason = "Transport chunk group ${chunk.groupId} changed source device address."
            )
        }
        if (group.framesByIndex.containsKey(chunk.chunkIndex)) {
            return BufferResult.DuplicateChunk(
                groupId = chunk.groupId,
                chunkIndex = chunk.chunkIndex
            )
        }
        if (group.framesByIndex.size >= maxFramesPerGroup) {
            groups.remove(chunk.groupId)
            return BufferResult.BufferOverflow(
                reason = "Transport chunk group ${chunk.groupId} exceeded the receive frame limit of $maxFramesPerGroup."
            )
        }

        group.framesByIndex[chunk.chunkIndex] = frame.frame
        if (group.framesByIndex.size < group.expectedChunks) {
            return BufferResult.Buffered(
                groupId = chunk.groupId,
                receivedChunks = group.framesByIndex.size,
                expectedChunks = group.expectedChunks
            )
        }

        val frames = (0 until group.expectedChunks).map { chunkIndex ->
            requireNotNull(group.framesByIndex[chunkIndex]) {
                "Transport chunk group ${chunk.groupId} is missing chunk index $chunkIndex."
            }
        }
        groups.remove(chunk.groupId)

        return BufferResult.Complete(
            groupId = chunk.groupId,
            sourceDeviceAddress = group.sourceDeviceAddress,
            frames = frames
        )
    }

    fun activeGroupCount(): Int {
        return groups.size
    }

    fun clear() {
        groups.clear()
    }

    sealed interface BufferResult {
        data class Buffered(
            val groupId: Int,
            val receivedChunks: Int,
            val expectedChunks: Int
        ) : BufferResult

        data class Complete(
            val groupId: Int,
            val sourceDeviceAddress: String?,
            val frames: List<BleGattTransportFrame>
        ) : BufferResult

        data class DuplicateChunk(
            val groupId: Int,
            val chunkIndex: Int
        ) : BufferResult

        data class InvalidChunk(
            val reason: String
        ) : BufferResult

        data class BufferOverflow(
            val reason: String
        ) : BufferResult
    }

    private class GroupState(
        val expectedChunks: Int,
        val sourceDeviceAddress: String?
    ) {
        val framesByIndex = LinkedHashMap<Int, BleGattTransportFrame>()
    }

    companion object {
        const val DEFAULT_MAX_GROUPS: Int = 8
        const val DEFAULT_MAX_FRAMES_PER_GROUP: Int =
            BleGattTransportFrameChunker.MAX_SUPPORTED_FRAMES_PER_GROUP
    }
}
