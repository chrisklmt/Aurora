package gr.hua.aurora.ble

private const val currentBleGattTransportPayloadVersion: Byte = 0x01
private const val currentBleGattTransportPayloadKind: Byte = 0x02

class BleGattTransportPayload private constructor(bytes: ByteArray) {
    private val bytes = bytes.copyOf()

    fun toByteArray(): ByteArray {
        return bytes.copyOf()
    }

    override fun equals(other: Any?): Boolean {
        return other is BleGattTransportPayload &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        return bytes.contentHashCode()
    }

    override fun toString(): String {
        val version = bytes[0].toInt() and 0xFF
        val kind = bytes[1].toInt() and 0xFF
        return "BleGattTransportPayload(version=$version, kind=$kind, payloadSize=${bytes.size})"
    }

    companion object {
        fun current(): BleGattTransportPayload {
            return BleGattTransportPayload(currentBytes())
        }

        fun matchesCurrent(bytes: ByteArray?): Boolean {
            return bytes?.contentEquals(currentBytes()) == true
        }

        private fun currentBytes(): ByteArray {
            return byteArrayOf(
                currentBleGattTransportPayloadVersion,
                currentBleGattTransportPayloadKind
            )
        }
    }
}
