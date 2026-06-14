package gr.hua.aurora.ble.discovery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BleStablePeerIdTest {
    @Test
    fun deriveFromPublicKeyBytesIsDeterministic() {
        val publicKeyBytes = ByteArray(65) { index -> index.toByte() }

        val first = BleStablePeerId.deriveFromPublicKeyBytes(publicKeyBytes)
        val second = BleStablePeerId.deriveFromPublicKeyBytes(publicKeyBytes)

        assertEquals(first, second)
        assertArrayEquals(first.toByteArray(), second.toByteArray())
        assertEquals(BleStablePeerId.sizeBytes, first.toByteArray().size)
    }

    @Test
    fun deriveFromPublicKeyBytesChangesWhenInputChanges() {
        val first = BleStablePeerId.deriveFromPublicKeyBytes(
            ByteArray(65) { index -> index.toByte() }
        )
        val second = BleStablePeerId.deriveFromPublicKeyBytes(
            ByteArray(65) { index -> (index + 1).toByte() }
        )

        assertNotEquals(first, second)
    }

    @Test
    fun toByteArrayReturnsDefensiveCopy() {
        val stablePeerId = BleStablePeerId.fromBytes(
            byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x09, 0x11, 0x22, 0x33)
        )

        val firstRead = stablePeerId.toByteArray()
        firstRead[0] = 0x7F
        val secondRead = stablePeerId.toByteArray()

        assertEquals(0x7F.toByte(), firstRead[0])
        assertArrayEquals(
            byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x09, 0x11, 0x22, 0x33),
            secondRead
        )
    }
}
