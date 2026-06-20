package gr.hua.aurora.ble.noop

import gr.hua.aurora.ble.transport.BleGattTransportFrameReassembler
import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlanBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NoOpBleTransportSenderTest {
    @Test
    fun sendReportsNotAvailable() {
        val results = mutableListOf<BleTransportSendResult>()
        val sender = NoOpBleTransportSender()
        val plan = planFor(byteArrayOf(0x05, 0x06, 0x07))

        sender.send(
            plan = plan,
            listener = object : BleTransportSender.Listener {
                override fun onSendResult(result: BleTransportSendResult) {
                    results += result
                }
            }
        )

        assertEquals(listOf(BleTransportSendResult.NotAvailable), results)
    }

    @Test
    fun repeatedSendCallsAreSafe() {
        val results = mutableListOf<BleTransportSendResult>()
        val sender = NoOpBleTransportSender()
        val plan = planFor(ByteArray(14) { index -> (index + 1).toByte() })

        sender.send(
            plan = plan,
            listener = object : BleTransportSender.Listener {
                override fun onSendResult(result: BleTransportSendResult) {
                    results += result
                }
            }
        )
        sender.send(
            plan = plan,
            listener = object : BleTransportSender.Listener {
                override fun onSendResult(result: BleTransportSendResult) {
                    results += result
                }
            }
        )

        assertEquals(
            listOf(
                BleTransportSendResult.NotAvailable,
                BleTransportSendResult.NotAvailable
            ),
            results
        )
    }

    @Test
    fun sendDoesNotMutatePlanFrames() {
        val sender = NoOpBleTransportSender()
        val originalBytes = ByteArray(17) { index -> (index + 3).toByte() }
        val plan = planFor(originalBytes)
        val before = BleGattTransportFrameReassembler.reassemble(plan.framesInSendOrder())

        sender.send(
            plan = plan,
            listener = object : BleTransportSender.Listener {
                override fun onSendResult(result: BleTransportSendResult) {
                }
            }
        )

        val after = BleGattTransportFrameReassembler.reassemble(plan.framesInSendOrder())

        assertArrayEquals(before, after)
        assertArrayEquals(originalBytes, after)
    }

    @Test
    fun failedResultRequiresNonBlankReason() {
        assertThrows(IllegalArgumentException::class.java) {
            BleTransportSendResult.Failed("")
        }
    }

    @Test
    fun failedResultPreservesReason() {
        val failed = BleTransportSendResult.Failed("peer rejected transport submission")

        assertEquals("peer rejected transport submission", failed.reason)
    }

    private fun planFor(encryptedBytes: ByteArray) =
        OutgoingBleTransportSendPlanBuilder.build(
            messageId = "plan-${encryptedBytes.size}",
            targetPeerId = "peer-alex",
            encryptedEnvelopeBytes = encryptedBytes,
            sourceCreatedAtMillis = 1_715_240_001L
        )
}
