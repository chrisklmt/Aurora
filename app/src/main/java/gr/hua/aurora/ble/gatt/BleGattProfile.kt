package gr.hua.aurora.ble.gatt

import java.util.UUID

object BleGattProfile {
    val serviceUuid: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ac")
    val transportCharacteristicUuid: UUID =
        UUID.fromString("12345678-1234-1234-1234-1234567890ad")
    val frameTransportCharacteristicUuid: UUID =
        UUID.fromString("12345678-1234-1234-1234-1234567890ae")
}
