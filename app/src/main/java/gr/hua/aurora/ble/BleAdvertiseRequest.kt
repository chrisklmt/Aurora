package gr.hua.aurora.ble

private const val maxLegacyPayloadBytes = 31

class BleAdvertiseRequest(payload: ByteArray) {
    private val payloadBytes = payload.copyOf().also { copiedPayload ->
        require(copiedPayload.isNotEmpty()) {
            "Advertising payload must not be empty."
        }
        require(copiedPayload.size <= maxLegacyPayloadBytes) {
            "Advertising payload must be at most $maxLegacyPayloadBytes bytes."
        }
    }

    val payload: ByteArray
        get() = payloadBytes.copyOf()

    override fun equals(other: Any?): Boolean {
        return other is BleAdvertiseRequest &&
            payloadBytes.contentEquals(other.payloadBytes)
    }

    override fun hashCode(): Int {
        return payloadBytes.contentHashCode()
    }

    override fun toString(): String {
        return "BleAdvertiseRequest(payloadSize=${payloadBytes.size})"
    }
}
