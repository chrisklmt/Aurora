package gr.hua.aurora.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleGattTransportPayloadTest {
    @Test
    fun currentPayloadEncodesExpectedVersionAndKind() {
        val payload = BleGattTransportPayload.current()

        assertArrayEquals(byteArrayOf(0x01, 0x02), payload.toByteArray())
    }

    @Test
    fun currentPayloadIsDeterministic() {
        val first = BleGattTransportPayload.current()
        val second = BleGattTransportPayload.current()

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertArrayEquals(first.toByteArray(), second.toByteArray())
    }

    @Test
    fun toByteArrayReturnsDefensiveCopy() {
        val payload = BleGattTransportPayload.current()

        val firstRead = payload.toByteArray()
        firstRead[0] = 0x09
        val secondRead = payload.toByteArray()

        assertArrayEquals(byteArrayOf(0x01, 0x02), secondRead)
        assertEquals(0x09.toByte(), firstRead[0])
    }

    @Test
    fun currentPayloadMatchesCurrent() {
        val payload = BleGattTransportPayload.current()

        assertTrue(BleGattTransportPayload.matchesCurrent(payload.toByteArray()))
    }

    @Test
    fun nullPayloadDoesNotMatchCurrent() {
        assertFalse(BleGattTransportPayload.matchesCurrent(null))
    }

    @Test
    fun emptyPayloadDoesNotMatchCurrent() {
        assertFalse(BleGattTransportPayload.matchesCurrent(byteArrayOf()))
    }

    @Test
    fun wrongVersionDoesNotMatchCurrent() {
        assertFalse(BleGattTransportPayload.matchesCurrent(byteArrayOf(0x02, 0x02)))
    }

    @Test
    fun wrongKindDoesNotMatchCurrent() {
        assertFalse(BleGattTransportPayload.matchesCurrent(byteArrayOf(0x01, 0x03)))
    }

    @Test
    fun wrongLengthDoesNotMatchCurrent() {
        assertFalse(BleGattTransportPayload.matchesCurrent(byteArrayOf(0x01, 0x02, 0x00)))
    }

    @Test
    fun matcherDoesNotMutateInput() {
        val bytes = byteArrayOf(0x01, 0x02)

        assertTrue(BleGattTransportPayload.matchesCurrent(bytes))
        assertArrayEquals(byteArrayOf(0x01, 0x02), bytes)
    }

    @Test
    fun toStringDoesNotExposeRawBytes() {
        val payload = BleGattTransportPayload.current()

        assertEquals(
            "BleGattTransportPayload(version=1, kind=2, payloadSize=2)",
            payload.toString()
        )
    }
}
