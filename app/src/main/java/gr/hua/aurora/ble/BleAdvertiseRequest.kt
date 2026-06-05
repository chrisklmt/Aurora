package gr.hua.aurora.ble

import java.util.UUID

private const val maxLegacyPayloadBytes = 31

class BleAdvertiseRequest(
    val serviceUuid: UUID,
    payload: ByteArray
) {
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
            serviceUuid == other.serviceUuid &&
            payloadBytes.contentEquals(other.payloadBytes)
    }

    override fun hashCode(): Int {
        return 31 * serviceUuid.hashCode() + payloadBytes.contentHashCode()
    }

    override fun toString(): String {
        return "BleAdvertiseRequest(serviceUuid=$serviceUuid, payloadSize=${payloadBytes.size})"
    }
}
