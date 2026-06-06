package gr.hua.aurora.ble

import java.util.UUID

object BleGattProfile {
    val serviceUuid: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ac")
    val transportCharacteristicUuid: UUID =
        UUID.fromString("12345678-1234-1234-1234-1234567890ad")
}
