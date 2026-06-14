package gr.hua.aurora.ble

private const val currentBleDiscoveryPayloadVersion: Byte = 0x01
private const val currentBleDiscoveryPayloadKind: Byte = 0x01
private const val legacyBleDiscoveryPayloadSizeBytes = 2
private const val stablePeerIdBleDiscoveryPayloadSizeBytes =
    legacyBleDiscoveryPayloadSizeBytes + BleStablePeerId.sizeBytes

class BleDiscoveryPayload private constructor(
    private val bytes: ByteArray,
    val stablePeerId: BleStablePeerId?
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
        fun current(stablePeerId: BleStablePeerId? = null): BleDiscoveryPayload {
            return BleDiscoveryPayload(
                currentBytes(stablePeerId),
                stablePeerId
            )
        }

        fun parse(bytes: ByteArray?): BleDiscoveryPayload? {
            val copiedBytes = bytes?.copyOf() ?: return null
            if (copiedBytes.size != legacyBleDiscoveryPayloadSizeBytes &&
                copiedBytes.size != stablePeerIdBleDiscoveryPayloadSizeBytes
            ) {
                return null
            }

            if (copiedBytes[0] != currentBleDiscoveryPayloadVersion ||
                copiedBytes[1] != currentBleDiscoveryPayloadKind
            ) {
                return null
            }

            val stablePeerId = if (copiedBytes.size == legacyBleDiscoveryPayloadSizeBytes) {
                null
            } else {
                BleStablePeerId.fromBytes(
                    copiedBytes.copyOfRange(
                        legacyBleDiscoveryPayloadSizeBytes,
                        stablePeerIdBleDiscoveryPayloadSizeBytes
                    )
                )
            }

            return BleDiscoveryPayload(
                bytes = copiedBytes,
                stablePeerId = stablePeerId
            )
        }

        fun matchesCurrent(bytes: ByteArray?): Boolean {
            return parse(bytes) != null
        }

        private fun currentBytes(stablePeerId: BleStablePeerId?): ByteArray {
            val markerBytes = byteArrayOf(
                currentBleDiscoveryPayloadVersion,
                currentBleDiscoveryPayloadKind
            )

            return if (stablePeerId == null) {
                markerBytes
            } else {
                markerBytes + stablePeerId.toByteArray()
            }
        }
    }
}
