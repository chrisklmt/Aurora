package gr.hua.aurora.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NoOpBleGattTransportFrameWriterTest {
    @Test
    fun writeReportsNotAvailable() {
        val results = mutableListOf<BleGattTransportFrameWriteResult>()
        val writer = NoOpBleGattTransportFrameWriter()
        val frame = checkNotNull(BleGattTransportFrame.create(body = byteArrayOf(0x05, 0x06)))

        writer.write(
            frame = frame,
            listener = object : BleGattTransportFrameWriter.Listener {
                override fun onWriteResult(result: BleGattTransportFrameWriteResult) {
                    results += result
                }
            }
        )

        assertEquals(listOf(BleGattTransportFrameWriteResult.NotAvailable), results)
    }

    @Test
    fun repeatedWriteCallsAreSafe() {
        val results = mutableListOf<BleGattTransportFrameWriteResult>()
        val writer = NoOpBleGattTransportFrameWriter()
        val frame = checkNotNull(BleGattTransportFrame.create(body = byteArrayOf(0x05, 0x06)))

        writer.write(
            frame = frame,
            listener = object : BleGattTransportFrameWriter.Listener {
                override fun onWriteResult(result: BleGattTransportFrameWriteResult) {
                    results += result
                }
            }
        )
        writer.write(
            frame = frame,
            listener = object : BleGattTransportFrameWriter.Listener {
                override fun onWriteResult(result: BleGattTransportFrameWriteResult) {
                    results += result
                }
            }
        )

        assertEquals(
            listOf(
                BleGattTransportFrameWriteResult.NotAvailable,
                BleGattTransportFrameWriteResult.NotAvailable
            ),
            results
        )
    }

    @Test
    fun writeDoesNotMutateFrame() {
        val writer = NoOpBleGattTransportFrameWriter()
        val frame = checkNotNull(BleGattTransportFrame.create(body = byteArrayOf(0x05, 0x06)))
        val before = frame.toByteArray()

        writer.write(
            frame = frame,
            listener = object : BleGattTransportFrameWriter.Listener {
                override fun onWriteResult(result: BleGattTransportFrameWriteResult) {
                }
            }
        )

        assertArrayEquals(before, frame.toByteArray())
    }
}
