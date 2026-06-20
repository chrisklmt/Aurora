package gr.hua.aurora.ble.transport

class BleGattTransportChunk private constructor(
    val groupId: Int,
    val chunkIndex: Int,
    val totalChunks: Int,
    payload: ByteArray
) {
    private val payload = payload.copyOf()

    init {
        require(groupId in 0..MAX_UNSIGNED_SHORT) {
            "Transport chunk groupId must be between 0 and $MAX_UNSIGNED_SHORT."
        }
        require(totalChunks in 1..MAX_UNSIGNED_SHORT) {
            "Transport chunk totalChunks must be between 1 and $MAX_UNSIGNED_SHORT."
        }
        require(chunkIndex in 0 until totalChunks) {
            "Transport chunk chunkIndex must be between 0 and ${totalChunks - 1}."
        }
        require(payload.isNotEmpty()) {
            "Transport chunk payload must not be empty."
        }
        require(payload.size <= MAX_PAYLOAD_SIZE) {
            "Transport chunk payload must be at most $MAX_PAYLOAD_SIZE bytes."
        }
    }

    fun payloadToByteArray(): ByteArray {
        return payload.copyOf()
    }

    fun toFrame(): BleGattTransportFrame? {
        return BleGattTransportFrame.create(body = toFrameBody())
    }

    private fun toFrameBody(): ByteArray {
        return byteArrayOf(
            CURRENT_VERSION,
            ((groupId ushr 8) and 0xFF).toByte(),
            (groupId and 0xFF).toByte(),
            ((chunkIndex ushr 8) and 0xFF).toByte(),
            (chunkIndex and 0xFF).toByte(),
            ((totalChunks ushr 8) and 0xFF).toByte(),
            (totalChunks and 0xFF).toByte()
        ) + payload
    }

    override fun equals(other: Any?): Boolean {
        return other is BleGattTransportChunk &&
            groupId == other.groupId &&
            chunkIndex == other.chunkIndex &&
            totalChunks == other.totalChunks &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = groupId
        result = 31 * result + chunkIndex
        result = 31 * result + totalChunks
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "BleGattTransportChunk(groupId=$groupId, chunkIndex=$chunkIndex, totalChunks=$totalChunks, payloadSize=${payload.size})"
    }

    companion object {
        private const val CURRENT_VERSION: Byte = 0x01
        private const val MAX_UNSIGNED_SHORT: Int = 0xFFFF
        const val HEADER_SIZE: Int = 7
        const val MAX_PAYLOAD_SIZE: Int = BleGattTransportFrame.MAX_BODY_SIZE - HEADER_SIZE

        fun create(
            groupId: Int,
            chunkIndex: Int,
            totalChunks: Int,
            payload: ByteArray
        ): BleGattTransportChunk? {
            if (
                groupId !in 0..MAX_UNSIGNED_SHORT ||
                totalChunks !in 1..MAX_UNSIGNED_SHORT ||
                chunkIndex !in 0 until totalChunks ||
                payload.isEmpty() ||
                payload.size > MAX_PAYLOAD_SIZE
            ) {
                return null
            }

            return BleGattTransportChunk(
                groupId = groupId,
                chunkIndex = chunkIndex,
                totalChunks = totalChunks,
                payload = payload
            )
        }

        fun parse(frame: BleGattTransportFrame?): BleGattTransportChunk? {
            if (frame == null || frame.kind != BleGattTransportFrame.Kind.Transport) {
                return null
            }

            val body = frame.bodyToByteArray()
            if (body.size <= HEADER_SIZE || body[0] != CURRENT_VERSION) {
                return null
            }

            val groupId = ((body[1].toInt() and 0xFF) shl 8) or (body[2].toInt() and 0xFF)
            val chunkIndex = ((body[3].toInt() and 0xFF) shl 8) or (body[4].toInt() and 0xFF)
            val totalChunks = ((body[5].toInt() and 0xFF) shl 8) or (body[6].toInt() and 0xFF)

            return create(
                groupId = groupId,
                chunkIndex = chunkIndex,
                totalChunks = totalChunks,
                payload = body.copyOfRange(HEADER_SIZE, body.size)
            )
        }
    }
}
