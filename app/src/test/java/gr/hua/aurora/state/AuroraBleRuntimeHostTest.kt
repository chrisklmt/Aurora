package gr.hua.aurora.state

import android.Manifest
import gr.hua.aurora.ble.noop.NoOpBleTransportSender
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import gr.hua.aurora.ble.transport.AndroidBleTransportSender
import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleGattTransportFrameWriteResult
import gr.hua.aurora.ble.transport.BleGattTransportFrameWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraBleRuntimeHostTest {
    @Test
    fun runtimeStartsWhenAvailabilityAndReadinessAreOnline() {
        assertTrue(
            shouldRunAuroraBleRuntime(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                bluetoothStatus = readyBluetoothStatus(),
                isAppVisible = true
            )
        )
    }

    @Test
    fun runtimeStaysStoppedWhenLocationIsDisabled() {
        assertFalse(
            shouldRunAuroraBleRuntime(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                bluetoothStatus = readyBluetoothStatus(
                    isLocationEnabled = false
                ),
                isAppVisible = true
            )
        )
    }

    @Test
    fun runtimeStaysStoppedWhenBluetoothIsDisabled() {
        assertFalse(
            shouldRunAuroraBleRuntime(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                bluetoothStatus = readyBluetoothStatus(
                    isBluetoothEnabled = false
                ),
                isAppVisible = true
            )
        )
    }

    @Test
    fun runtimeStaysStoppedWhenUserPreferenceIsOffline() {
        assertFalse(
            shouldRunAuroraBleRuntime(
                desiredAvailability = AuroraAvailabilityPreference.OFFLINE,
                bluetoothStatus = readyBluetoothStatus(),
                isAppVisible = true
            )
        )
    }

    @Test
    fun runtimeStaysStoppedWhenAppIsNotVisible() {
        assertFalse(
            shouldRunAuroraBleRuntime(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                bluetoothStatus = readyBluetoothStatus(),
                isAppVisible = false
            )
        )
    }

    @Test
    fun runtimeExposesNoOpSenderWhenFrameWriterIsUnavailable() {
        val sender = createAuroraBleTransportSender(transportFrameWriter = null)

        assertTrue(sender is NoOpBleTransportSender)
    }

    @Test
    fun runtimeExposesAndroidSenderWhenFrameWriterIsAvailable() {
        val sender = createAuroraBleTransportSender(
            transportFrameWriter = object : BleGattTransportFrameWriter {
                override fun write(
                    frame: BleGattTransportFrame,
                    listener: BleGattTransportFrameWriter.Listener
                ) {
                    listener.onWriteResult(BleGattTransportFrameWriteResult.Accepted)
                }
            }
        )

        assertTrue(sender is AndroidBleTransportSender)
    }

    @Test
    fun runtimeStartDecisionStaysIndependentFromTransportSenderAvailability() {
        val shouldRunWithoutWriter = shouldRunAuroraBleRuntime(
            desiredAvailability = AuroraAvailabilityPreference.ONLINE,
            bluetoothStatus = readyBluetoothStatus(),
            isAppVisible = true
        )
        createAuroraBleTransportSender(transportFrameWriter = null)
        val shouldRunWithWriter = shouldRunAuroraBleRuntime(
            desiredAvailability = AuroraAvailabilityPreference.ONLINE,
            bluetoothStatus = readyBluetoothStatus(),
            isAppVisible = true
        )

        assertTrue(shouldRunWithoutWriter)
        assertTrue(shouldRunWithWriter)
    }

    private fun readyBluetoothStatus(
        isBluetoothEnabled: Boolean = true,
        isLocationEnabled: Boolean = true
    ): BluetoothPermissionStatus {
        return BluetoothPermissionStatus(
            requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            missingPermissions = emptySet(),
            isBluetoothEnabled = isBluetoothEnabled,
            isLocationEnabled = isLocationEnabled
        )
    }
}
