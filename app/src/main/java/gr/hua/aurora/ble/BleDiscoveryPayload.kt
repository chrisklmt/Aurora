package gr.hua.aurora.ble

private const val currentBleDiscoveryPayloadVersion: Byte = 0x01
private const val currentBleDiscoveryPayloadKind: Byte = 0x01

class BleDiscoveryPayload private constructor(
    private val bytes: ByteArray
) {
    fun toByteArray(): ByteArray {
        return bytes.copyOf()
    }

    override fun equals(other: Any?): Boolean {
        return other is BleDiscoveryPayload &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        return bytes.contentHashCode()
    }

    override fun toString(): String {
        val version = bytes[0].toInt() and 0xFF
        val kind = bytes[1].toInt() and 0xFF
        return "BleDiscoveryPayload(version=$version, kind=$kind, payloadSize=${bytes.size})"
    }

    companion object {
        fun current(): BleDiscoveryPayload {
            return BleDiscoveryPayload(
                byteArrayOf(
                    currentBleDiscoveryPayloadVersion,
                    currentBleDiscoveryPayloadKind
                )
            )
        }
    }
}
