package gr.hua.aurora.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BleDiscoveryPayloadTest {
    private val serviceUuid = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val stablePeerId = BleStablePeerId.fromBytes(
        byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x7F)
    )

    @Test
    fun currentPayloadMatchesCurrent() {
        val payload = BleDiscoveryPayload.current()

        assertTrue(BleDiscoveryPayload.matchesCurrent(payload.toByteArray()))
    }

    @Test
    fun nullPayloadDoesNotMatchCurrent() {
        assertFalse(BleDiscoveryPayload.matchesCurrent(null))
    }

    @Test
    fun wrongVersionDoesNotMatchCurrent() {
        assertFalse(BleDiscoveryPayload.matchesCurrent(byteArrayOf(0x02, 0x01)))
    }

    @Test
    fun wrongKindDoesNotMatchCurrent() {
        assertFalse(BleDiscoveryPayload.matchesCurrent(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun wrongLengthDoesNotMatchCurrent() {
        assertFalse(BleDiscoveryPayload.matchesCurrent(byteArrayOf(0x01, 0x01, 0x00)))
    }

    @Test
    fun emptyPayloadDoesNotMatchCurrent() {
        assertFalse(BleDiscoveryPayload.matchesCurrent(byteArrayOf()))
    }

    @Test
    fun matcherDoesNotMutateInput() {
        val bytes = byteArrayOf(0x01, 0x01)

        assertTrue(BleDiscoveryPayload.matchesCurrent(bytes))
        assertArrayEquals(byteArrayOf(0x01, 0x01), bytes)
    }

    @Test
    fun currentPayloadEncodesExpectedVersionAndKind() {
        val payload = BleDiscoveryPayload.current()

        assertArrayEquals(byteArrayOf(0x01, 0x01), payload.toByteArray())
    }

    @Test
    fun payloadWithStablePeerIdMatchesCurrent() {
        val payload = BleDiscoveryPayload.current(stablePeerId)

        assertTrue(BleDiscoveryPayload.matchesCurrent(payload.toByteArray()))
    }

    @Test
    fun legacyPayloadParsesWithoutStablePeerId() {
        val payload = BleDiscoveryPayload.parse(byteArrayOf(0x01, 0x01))

        assertEquals(null, payload?.stablePeerId)
        assertArrayEquals(byteArrayOf(0x01, 0x01), payload?.toByteArray())
    }

    @Test
    fun payloadWithStablePeerIdParsesExpectedFields() {
        val payload = BleDiscoveryPayload.current(stablePeerId)
        val parsed = BleDiscoveryPayload.parse(payload.toByteArray())

        assertEquals(stablePeerId, parsed?.stablePeerId)
        assertArrayEquals(payload.toByteArray(), parsed?.toByteArray())
    }

    @Test
    fun toByteArrayReturnsDefensiveCopy() {
        val payload = BleDiscoveryPayload.current()

        val firstRead = payload.toByteArray()
        firstRead[0] = 0x09
        val secondRead = payload.toByteArray()

        assertArrayEquals(byteArrayOf(0x01, 0x01), secondRead)
        assertEquals(0x09.toByte(), firstRead[0])
    }

    @Test
    fun currentPayloadIsDeterministic() {
        val first = BleDiscoveryPayload.current()
        val second = BleDiscoveryPayload.current()

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertArrayEquals(first.toByteArray(), second.toByteArray())
    }

    @Test
    fun payloadFitsBleAdvertiseRequestLimit() {
        val payload = BleDiscoveryPayload.current()

        val request = BleAdvertiseRequest(
            serviceUuid = serviceUuid,
            payload = payload.toByteArray()
        )

        assertArrayEquals(byteArrayOf(0x01, 0x01), request.payload)
    }

    @Test
    fun payloadWithStablePeerIdFitsBleAdvertiseRequestLimit() {
        val payload = BleDiscoveryPayload.current(stablePeerId)

        val request = BleAdvertiseRequest(
            serviceUuid = serviceUuid,
            payload = payload.toByteArray()
        )

        assertArrayEquals(payload.toByteArray(), request.payload)
    }

    @Test
    fun toStringDoesNotExposeRawBytes() {
        val payload = BleDiscoveryPayload.current()

        assertEquals(
            "BleDiscoveryPayload(version=1, kind=1, payloadSize=2)",
            payload.toString()
        )
    }
}
