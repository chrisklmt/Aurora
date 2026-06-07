package gr.hua.aurora.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoOpBleGattTransportFrameReaderTest {
    @Test
    fun readReportsNotAvailable() {
        val results = mutableListOf<BleGattTransportFrameReadResult>()
        val reader = NoOpBleGattTransportFrameReader()

        reader.read(
            listener = object : BleGattTransportFrameReader.Listener {
                override fun onReadResult(result: BleGattTransportFrameReadResult) {
                    results += result
                }
            }
        )

        assertEquals(listOf(BleGattTransportFrameReadResult.NotAvailable), results)
    }

    @Test
    fun repeatedReadCallsAreSafe() {
        val results = mutableListOf<BleGattTransportFrameReadResult>()
        val reader = NoOpBleGattTransportFrameReader()

        reader.read(
            listener = object : BleGattTransportFrameReader.Listener {
                override fun onReadResult(result: BleGattTransportFrameReadResult) {
                    results += result
                }
            }
        )
        reader.read(
            listener = object : BleGattTransportFrameReader.Listener {
                override fun onReadResult(result: BleGattTransportFrameReadResult) {
                    results += result
                }
            }
        )

        assertEquals(
            listOf(
                BleGattTransportFrameReadResult.NotAvailable,
                BleGattTransportFrameReadResult.NotAvailable
            ),
            results
        )
    }

    @Test
    fun readDoesNotFabricateFrame() {
        val frames = mutableListOf<BleGattTransportFrame>()
        val reader = NoOpBleGattTransportFrameReader()

        reader.read(
            listener = object : BleGattTransportFrameReader.Listener {
                override fun onReadResult(result: BleGattTransportFrameReadResult) {
                    if (result is BleGattTransportFrameReadResult.FrameAvailable) {
                        frames += result.frame
                    }
                }
            }
        )

        assertTrue(frames.isEmpty())
    }
}
