package gr.hua.aurora.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class BleAdvertiseRequestTest {
    private val serviceUuid = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val otherServiceUuid = UUID.fromString("87654321-4321-4321-4321-ba0987654321")

    @Test
    fun acceptsServiceUuidAndNonEmptyPayloadWithinLimit() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)

        val request = BleAdvertiseRequest(serviceUuid, payload)

        assertEquals(serviceUuid, request.serviceUuid)
        assertArrayEquals(payload, request.payload)
    }

    @Test
    fun rejectsEmptyPayload() {
        try {
            BleAdvertiseRequest(serviceUuid, byteArrayOf())
            fail("Expected empty payload to be rejected.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun rejectsPayloadAboveLegacyLimit() {
        try {
            BleAdvertiseRequest(serviceUuid, ByteArray(32) { 0x01 })
            fail("Expected oversized payload to be rejected.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun storesDefensiveCopyOfInputPayload() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val request = BleAdvertiseRequest(serviceUuid, payload)

        payload[0] = 0x09

        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), request.payload)
    }

    @Test
    fun returnsDefensiveCopyOfPayload() {
        val request = BleAdvertiseRequest(serviceUuid, byteArrayOf(0x01, 0x02, 0x03))

        val firstRead = request.payload
        firstRead[0] = 0x09
        val secondRead = request.payload

        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), secondRead)
        assertEquals(0x09.toByte(), firstRead[0])
    }

    @Test
    fun equalityUsesServiceUuidAndPayloadContent() {
        val first = BleAdvertiseRequest(serviceUuid, byteArrayOf(0x01, 0x02, 0x03))
        val second = BleAdvertiseRequest(serviceUuid, byteArrayOf(0x01, 0x02, 0x03))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun differentServiceUuidIsNotEqual() {
        val first = BleAdvertiseRequest(serviceUuid, byteArrayOf(0x01, 0x02, 0x03))
        val second = BleAdvertiseRequest(otherServiceUuid, byteArrayOf(0x01, 0x02, 0x03))

        assertNotEquals(first, second)
    }

    @Test
    fun toStringDoesNotExposePayloadBytes() {
        val request = BleAdvertiseRequest(serviceUuid, byteArrayOf(0x11, 0x22, 0x33))

        assertEquals(
            "BleAdvertiseRequest(serviceUuid=$serviceUuid, payloadSize=3)",
            request.toString()
        )
    }
}
