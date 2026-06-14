package gr.hua.aurora.ble.gatt

import gr.hua.aurora.ble.discovery.BleDiscoveryService
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
    fun frameTransportCharacteristicUuidIsStable() {
        assertEquals(
            UUID.fromString("12345678-1234-1234-1234-1234567890ae"),
            BleGattProfile.frameTransportCharacteristicUuid
        )
    }

    @Test
    fun gattProfileUuidsAreDistinct() {
        assertNotEquals(
            BleGattProfile.serviceUuid,
            BleGattProfile.transportCharacteristicUuid
        )
        assertNotEquals(
            BleGattProfile.serviceUuid,
            BleGattProfile.frameTransportCharacteristicUuid
        )
        assertNotEquals(
            BleGattProfile.transportCharacteristicUuid,
            BleGattProfile.frameTransportCharacteristicUuid
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
