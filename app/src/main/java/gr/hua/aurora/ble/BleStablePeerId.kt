package gr.hua.aurora.ble

import java.security.MessageDigest

private const val stablePeerIdSizeBytes = 8

class BleStablePeerId private constructor(
    private val bytes: ByteArray
) {
    fun toByteArray(): ByteArray {
        return bytes.copyOf()
    }

    override fun equals(other: Any?): Boolean {
        return other is BleStablePeerId &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        return bytes.contentHashCode()
    }

    override fun toString(): String {
        return "BleStablePeerId(size=${bytes.size})"
    }

    internal fun toHexKey(): String {
        return bytes.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }

    companion object {
        const val sizeBytes: Int = stablePeerIdSizeBytes

        fun fromBytes(bytes: ByteArray): BleStablePeerId {
            require(bytes.size == stablePeerIdSizeBytes) {
                "Stable peer id must be exactly $stablePeerIdSizeBytes bytes."
            }

            return BleStablePeerId(
                bytes.copyOf()
            )
        }

        fun deriveFromPublicKeyBytes(publicKeyBytes: ByteArray): BleStablePeerId {
            require(publicKeyBytes.isNotEmpty()) {
                "Public key bytes must not be empty."
            }

            return fromBytes(
                MessageDigest.getInstance("SHA-256")
                    .digest(publicKeyBytes)
                    .copyOf(stablePeerIdSizeBytes)
            )
        }
    }
}
