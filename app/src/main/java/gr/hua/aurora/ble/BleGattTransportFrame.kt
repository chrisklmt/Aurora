package gr.hua.aurora.ble

class BleGattTransportFrame private constructor(
    val kind: Kind,
    body: ByteArray
) {
    private val body = body.copyOf()

    fun bodyToByteArray(): ByteArray {
        return body.copyOf()
    }

    fun toByteArray(): ByteArray {
        return byteArrayOf(
            CURRENT_VERSION,
            kind.encoded,
            body.size.toByte()
        ) + body
    }

    override fun equals(other: Any?): Boolean {
        return other is BleGattTransportFrame &&
            kind == other.kind &&
            body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        return 31 * kind.hashCode() + body.contentHashCode()
    }

    override fun toString(): String {
        val version = CURRENT_VERSION.toInt() and 0xFF
        return "BleGattTransportFrame(version=$version, kind=${kind.name}, bodySize=${body.size})"
    }

    enum class Kind(val encoded: Byte) {
        Transport(0x01);

        companion object {
            fun fromEncoded(encoded: Byte): Kind? {
                return entries.firstOrNull { kind -> kind.encoded == encoded }
            }
        }
    }

    companion object {
        private const val CURRENT_VERSION: Byte = 0x01
        const val MAX_ENCODED_SIZE: Int = 20
        const val HEADER_SIZE: Int = 3
        const val MAX_BODY_SIZE: Int = MAX_ENCODED_SIZE - HEADER_SIZE

        fun create(
            kind: Kind = Kind.Transport,
            body: ByteArray
        ): BleGattTransportFrame? {
            if (body.size > MAX_BODY_SIZE) {
                return null
            }

            return BleGattTransportFrame(kind, body)
        }

        fun parse(bytes: ByteArray?): BleGattTransportFrame? {
            if (bytes == null || bytes.size < HEADER_SIZE || bytes.size > MAX_ENCODED_SIZE) {
                return null
            }

            if (bytes[0] != CURRENT_VERSION) {
                return null
            }

            val kind = Kind.fromEncoded(bytes[1]) ?: return null
            val declaredLength = bytes[2].toInt() and 0xFF
            if (declaredLength > MAX_BODY_SIZE) {
                return null
            }

            if (bytes.size != HEADER_SIZE + declaredLength) {
                return null
            }

            return BleGattTransportFrame(
                kind = kind,
                body = bytes.copyOfRange(HEADER_SIZE, bytes.size)
            )
        }
    }
}
