package gr.hua.aurora.ble

import java.util.UUID

private const val maxLegacyServiceDataPayloadBytes = 12

class BleAdvertiseRequest(
    val serviceUuid: UUID,
    payload: ByteArray
) {
    private val payloadBytes = payload.copyOf().also { copiedPayload ->
        require(copiedPayload.isNotEmpty()) {
            "Advertising payload must not be empty."
        }
        require(copiedPayload.size <= maxLegacyServiceDataPayloadBytes) {
            "Advertising payload must be at most $maxLegacyServiceDataPayloadBytes bytes."
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
