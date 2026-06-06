package gr.hua.aurora.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class NoOpBleGattTransportReaderTest {
    @Test
    fun readReportsNotAvailable() {
        val results = mutableListOf<BleGattTransportReadResult>()
        val reader = NoOpBleGattTransportReader()

        reader.read(
            listener = object : BleGattTransportReader.Listener {
                override fun onReadResult(result: BleGattTransportReadResult) {
                    results += result
                }
            }
        )

        assertEquals(listOf(BleGattTransportReadResult.NotAvailable), results)
    }

    @Test
    fun repeatedReadCallsAreSafe() {
        val results = mutableListOf<BleGattTransportReadResult>()
        val reader = NoOpBleGattTransportReader()

        reader.read(
            listener = object : BleGattTransportReader.Listener {
                override fun onReadResult(result: BleGattTransportReadResult) {
                    results += result
                }
            }
        )
        reader.read(
            listener = object : BleGattTransportReader.Listener {
                override fun onReadResult(result: BleGattTransportReadResult) {
                    results += result
                }
            }
        )

        assertEquals(
            listOf(
                BleGattTransportReadResult.NotAvailable,
                BleGattTransportReadResult.NotAvailable
            ),
            results
        )
    }
}
