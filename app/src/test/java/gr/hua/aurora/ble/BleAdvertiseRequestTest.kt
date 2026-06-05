package gr.hua.aurora.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BleAdvertiseRequestTest {
    @Test
    fun acceptsNonEmptyPayloadWithinLimit() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)

        val request = BleAdvertiseRequest(payload)

        assertArrayEquals(payload, request.payload)
    }

    @Test
    fun rejectsEmptyPayload() {
        try {
            BleAdvertiseRequest(byteArrayOf())
            fail("Expected empty payload to be rejected.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun rejectsPayloadAboveLegacyLimit() {
        try {
            BleAdvertiseRequest(ByteArray(32) { 0x01 })
            fail("Expected oversized payload to be rejected.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun storesDefensiveCopyOfInputPayload() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val request = BleAdvertiseRequest(payload)

        payload[0] = 0x09

        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), request.payload)
    }

    @Test
    fun returnsDefensiveCopyOfPayload() {
        val request = BleAdvertiseRequest(byteArrayOf(0x01, 0x02, 0x03))

        val firstRead = request.payload
        firstRead[0] = 0x09
        val secondRead = request.payload

        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), secondRead)
        assertEquals(0x09.toByte(), firstRead[0])
    }
}
