package gr.hua.aurora.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleGattTransportFrameTest {
    @Test
    fun createEncodesDeterministicBytesForSmallBody() {
        val frame = checkNotNull(BleGattTransportFrame.create(body = byteArrayOf(0x05, 0x06)))

        assertArrayEquals(
            byteArrayOf(0x01, 0x01, 0x02, 0x05, 0x06),
            frame.toByteArray()
        )
    }

    @Test
    fun parseValidEncodedBytesRoundTripsToSameFrame() {
        val frame = checkNotNull(BleGattTransportFrame.create(body = byteArrayOf(0x05, 0x06)))
        val parsed = checkNotNull(BleGattTransportFrame.parse(frame.toByteArray()))

        assertEquals(frame, parsed)
        assertArrayEquals(frame.bodyToByteArray(), parsed.bodyToByteArray())
        assertArrayEquals(frame.toByteArray(), parsed.toByteArray())
    }

    @Test
    fun bodyToByteArrayReturnsDefensiveCopy() {
        val frame = checkNotNull(BleGattTransportFrame.create(body = byteArrayOf(0x05, 0x06)))

        val firstRead = frame.bodyToByteArray()
        firstRead[0] = 0x09
        val secondRead = frame.bodyToByteArray()

        assertArrayEquals(byteArrayOf(0x05, 0x06), secondRead)
        assertEquals(0x09.toByte(), firstRead[0])
    }

    @Test
    fun toByteArrayReturnsDefensiveCopy() {
        val frame = checkNotNull(BleGattTransportFrame.create(body = byteArrayOf(0x05, 0x06)))

        val firstRead = frame.toByteArray()
        firstRead[3] = 0x09
        val secondRead = frame.toByteArray()

        assertArrayEquals(byteArrayOf(0x01, 0x01, 0x02, 0x05, 0x06), secondRead)
        assertEquals(0x09.toByte(), firstRead[3])
    }

    @Test
    fun createRejectsOversizedBody() {
        val oversizedBody = ByteArray(BleGattTransportFrame.MAX_BODY_SIZE + 1) { 0x01 }

        assertNull(BleGattTransportFrame.create(body = oversizedBody))
    }

    @Test
    fun parseRejectsNull() {
        assertNull(BleGattTransportFrame.parse(null))
    }

    @Test
    fun parseRejectsTooShortInput() {
        assertNull(BleGattTransportFrame.parse(byteArrayOf(0x01, 0x01)))
    }

    @Test
    fun parseRejectsOversizedInput() {
        val oversizedInput = ByteArray(BleGattTransportFrame.MAX_ENCODED_SIZE + 1) { 0x01 }

        assertNull(BleGattTransportFrame.parse(oversizedInput))
    }

    @Test
    fun parseRejectsWrongVersion() {
        assertNull(BleGattTransportFrame.parse(byteArrayOf(0x02, 0x01, 0x00)))
    }

    @Test
    fun parseRejectsUnknownKind() {
        assertNull(BleGattTransportFrame.parse(byteArrayOf(0x01, 0x02, 0x00)))
    }

    @Test
    fun parseRejectsMismatchedDeclaredLength() {
        assertNull(BleGattTransportFrame.parse(byteArrayOf(0x01, 0x01, 0x02, 0x05)))
    }

    @Test
    fun parseAcceptsEmptyBodyWithinFixedShape() {
        val frame = checkNotNull(BleGattTransportFrame.parse(byteArrayOf(0x01, 0x01, 0x00)))

        assertEquals(BleGattTransportFrame.Kind.Transport, frame.kind)
        assertArrayEquals(byteArrayOf(), frame.bodyToByteArray())
        assertArrayEquals(byteArrayOf(0x01, 0x01, 0x00), frame.toByteArray())
    }

    @Test
    fun createAndParseUseDefensiveCopies() {
        val createBody = byteArrayOf(0x05, 0x06)
        val created = checkNotNull(BleGattTransportFrame.create(body = createBody))
        createBody[0] = 0x09

        val encoded = byteArrayOf(0x01, 0x01, 0x02, 0x05, 0x06)
        val parsed = checkNotNull(BleGattTransportFrame.parse(encoded))
        encoded[3] = 0x09

        assertArrayEquals(byteArrayOf(0x05, 0x06), created.bodyToByteArray())
        assertArrayEquals(byteArrayOf(0x05, 0x06), parsed.bodyToByteArray())
    }

    @Test
    fun equalsAndHashCodeUseContent() {
        val first = BleGattTransportFrame.create(body = byteArrayOf(0x05, 0x06))
        val second = BleGattTransportFrame.create(body = byteArrayOf(0x05, 0x06))
        val third = BleGattTransportFrame.create(body = byteArrayOf(0x05, 0x07))

        assertEquals(first, second)
        assertEquals(first?.hashCode(), second?.hashCode())
        assertNotEquals(first, third)
    }

    @Test
    fun toStringDoesNotDumpRawBodyBytes() {
        val frame = checkNotNull(BleGattTransportFrame.create(body = byteArrayOf(0x05, 0x06)))

        assertEquals(
            "BleGattTransportFrame(version=1, kind=Transport, bodySize=2)",
            frame.toString()
        )
        assertFalse(frame.toString().contains("0x05"))
        assertFalse(frame.toString().contains("0x06"))
    }

    @Test
    fun supportedKindIsEncodedAsOne() {
        assertEquals(0x01.toByte(), BleGattTransportFrame.Kind.Transport.encoded)
    }

    @Test
    fun maxEncodedSizeIsTwentyBytes() {
        assertEquals(20, BleGattTransportFrame.MAX_ENCODED_SIZE)
        assertEquals(17, BleGattTransportFrame.MAX_BODY_SIZE)
        assertEquals(3, BleGattTransportFrame.HEADER_SIZE)
    }

    @Test
    fun parseRejectsDeclaredLengthLargerThanBodyLimit() {
        assertNull(BleGattTransportFrame.parse(byteArrayOf(0x01, 0x01, 0x12)))
    }

    @Test
    fun parseRejectsEncodedSizeLargerThanDeclaredLengthShape() {
        assertNull(BleGattTransportFrame.parse(byteArrayOf(0x01, 0x01, 0x00, 0x05)))
    }

    @Test
    fun createKeepsKindTransportByDefault() {
        val frame = BleGattTransportFrame.create(body = byteArrayOf())

        assertNotNull(frame)
        assertTrue(frame?.kind == BleGattTransportFrame.Kind.Transport)
    }
}
