package gr.hua.aurora.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

class BleGattProfileTest {
    @Test
    fun serviceUuidIsStable() {
        assertEquals(
            UUID.fromString("12345678-1234-1234-1234-1234567890ac"),
            BleGattProfile.serviceUuid
        )
    }

    @Test
    fun transportCharacteristicUuidIsStable() {
        assertEquals(
            UUID.fromString("12345678-1234-1234-1234-1234567890ad"),
            BleGattProfile.transportCharacteristicUuid
        )
    }

    @Test
    fun serviceAndTransportCharacteristicUuidsAreDistinct() {
        assertNotEquals(
            BleGattProfile.serviceUuid,
            BleGattProfile.transportCharacteristicUuid
        )
    }

    @Test
    fun serviceUuidDiffersFromDiscoveryServiceUuid() {
        assertNotEquals(
            BleGattProfile.serviceUuid,
            BleDiscoveryService.serviceUuid
        )
    }
}
