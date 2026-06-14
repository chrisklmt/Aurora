package gr.hua.aurora.ble.noop

import gr.hua.aurora.ble.transport.BleGattTransportPayload
import gr.hua.aurora.ble.transport.BleGattTransportWriteResult
import gr.hua.aurora.ble.transport.BleGattTransportWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NoOpBleGattTransportWriterTest {
    @Test
    fun writeReportsNotAvailable() {
        val results = mutableListOf<BleGattTransportWriteResult>()
        val writer = NoOpBleGattTransportWriter()

        writer.write(
            payload = BleGattTransportPayload.current(),
            listener = object : BleGattTransportWriter.Listener {
                override fun onWriteResult(result: BleGattTransportWriteResult) {
                    results += result
                }
            }
        )

        assertEquals(listOf(BleGattTransportWriteResult.NotAvailable), results)
    }

    @Test
    fun repeatedWriteCallsAreSafe() {
        val results = mutableListOf<BleGattTransportWriteResult>()
        val writer = NoOpBleGattTransportWriter()
        val payload = BleGattTransportPayload.current()

        writer.write(
            payload = payload,
            listener = object : BleGattTransportWriter.Listener {
                override fun onWriteResult(result: BleGattTransportWriteResult) {
                    results += result
                }
            }
        )
        writer.write(
            payload = payload,
            listener = object : BleGattTransportWriter.Listener {
                override fun onWriteResult(result: BleGattTransportWriteResult) {
                    results += result
                }
            }
        )

        assertEquals(
            listOf(
                BleGattTransportWriteResult.NotAvailable,
                BleGattTransportWriteResult.NotAvailable
            ),
            results
        )
    }

    @Test
    fun writeDoesNotMutatePayload() {
        val writer = NoOpBleGattTransportWriter()
        val payload = BleGattTransportPayload.current()
        val before = payload.toByteArray()

        writer.write(
            payload = payload,
            listener = object : BleGattTransportWriter.Listener {
                override fun onWriteResult(result: BleGattTransportWriteResult) {
                }
            }
        )

        assertArrayEquals(before, payload.toByteArray())
    }
}
