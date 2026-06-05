package gr.hua.aurora.ble

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class BleDiscoveryServiceTest {
    @Test
    fun serviceUuidIsStable() {
        assertEquals(
            UUID.fromString("12345678-1234-1234-1234-1234567890ab"),
            BleDiscoveryService.serviceUuid
        )
    }
}
