package gr.hua.aurora.ble.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBleTransportSenderTest {
    @Test
    fun sendReturnsNotAvailableWhenNoWriterIsAvailable() {
        val sender = AndroidBleTransportSender(frameWriter = null)
        val results = mutableListOf<BleTransportSendResult>()
        val plan = OutgoingBleTransportSendPlanBuilder.build(
            messageId = "android-sender-1",
            targetPeerId = "peer-a",
            encryptedEnvelopeBytes = ByteArray(4) { index -> (index + 1).toByte() }
        )

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
    fun sendWritesFramesInStableOrder() {
        val writtenFrames = mutableListOf<BleGattTransportFrame>()
        val results = mutableListOf<BleTransportSendResult>()
        val plan = OutgoingBleTransportSendPlanBuilder.build(
            messageId = "android-sender-2",
            targetPeerId = "peer-b",
            encryptedEnvelopeBytes = ByteArray(BleGattTransportChunk.MAX_PAYLOAD_SIZE * 2 + 3) {
                index -> (index + 3).toByte()
            }
        )
        val frameWriter = object : BleGattTransportFrameWriter {
            override fun write(
                frame: BleGattTransportFrame,
                listener: BleGattTransportFrameWriter.Listener
            ) {
                writtenFrames += frame
                listener.onWriteResult(BleGattTransportFrameWriteResult.Accepted)
            }
        }
        val sender = AndroidBleTransportSender(frameWriter)

        sender.send(
            plan = plan,
            listener = object : BleTransportSender.Listener {
                override fun onSendResult(result: BleTransportSendResult) {
                    results += result
                }
            }
        )

        assertEquals(plan.framesInSendOrder(), writtenFrames)
        assertEquals(listOf(BleTransportSendResult.QueuedLocally), results)
    }

    @Test
    fun sendStopsOnFirstFailedFrame() {
        val writtenFrames = mutableListOf<BleGattTransportFrame>()
        val results = mutableListOf<BleTransportSendResult>()
        val plan = OutgoingBleTransportSendPlanBuilder.build(
            messageId = "android-sender-3",
            targetPeerId = "peer-c",
            encryptedEnvelopeBytes = ByteArray(BleGattTransportChunk.MAX_PAYLOAD_SIZE * 3 + 2) {
                index -> (index + 5).toByte()
            }
        )
        val frameWriter = object : BleGattTransportFrameWriter {
            override fun write(
                frame: BleGattTransportFrame,
                listener: BleGattTransportFrameWriter.Listener
            ) {
                writtenFrames += frame
                val result = if (writtenFrames.size == 2) {
                    BleGattTransportFrameWriteResult.NotAvailable
                } else {
                    BleGattTransportFrameWriteResult.Accepted
                }
                listener.onWriteResult(result)
            }
        }
        val sender = AndroidBleTransportSender(frameWriter)

        sender.send(
            plan = plan,
            listener = object : BleTransportSender.Listener {
                override fun onSendResult(result: BleTransportSendResult) {
                    results += result
                }
            }
        )

        assertEquals(plan.framesInSendOrder().take(2), writtenFrames)
        assertEquals(1, results.size)
        assertEquals(
            BleTransportSendResult.Failed(
                "Frame 2 of ${plan.framesInSendOrder().size} for messageId=${plan.messageId} was not accepted."
            ),
            results.single()
        )
    }

    @Test
    fun sendReturnsQueuedLocallyOnlyAfterAllLocalWritesSucceed() {
        val results = mutableListOf<BleTransportSendResult>()
        var acceptedWriteCount = 0
        val plan = OutgoingBleTransportSendPlanBuilder.build(
            messageId = "android-sender-4",
            targetPeerId = "peer-d",
            encryptedEnvelopeBytes = ByteArray(BleGattTransportChunk.MAX_PAYLOAD_SIZE * 2 + 1) {
                index -> (index + 7).toByte()
            }
        )
        val frameWriter = object : BleGattTransportFrameWriter {
            override fun write(
                frame: BleGattTransportFrame,
                listener: BleGattTransportFrameWriter.Listener
            ) {
                acceptedWriteCount += 1
                listener.onWriteResult(BleGattTransportFrameWriteResult.Accepted)
            }
        }
        val sender = AndroidBleTransportSender(frameWriter)

        sender.send(
            plan = plan,
            listener = object : BleTransportSender.Listener {
                override fun onSendResult(result: BleTransportSendResult) {
                    results += result
                }
            }
        )

        assertEquals(plan.framesInSendOrder().size, acceptedWriteCount)
        assertEquals(listOf(BleTransportSendResult.QueuedLocally), results)
    }

    @Test
    fun sendDoesNotMutatePlan() {
        val originalBytes = ByteArray(BleGattTransportChunk.MAX_PAYLOAD_SIZE * 2 + 4) { index ->
            (index + 9).toByte()
        }
        val plan = OutgoingBleTransportSendPlanBuilder.build(
            messageId = "android-sender-5",
            targetPeerId = "peer-e",
            encryptedEnvelopeBytes = originalBytes,
            sourceCreatedAtMillis = 1_715_250_001L
        )
        val before = BleGattTransportFrameReassembler.reassemble(plan.framesInSendOrder())
        val sender = AndroidBleTransportSender(
            frameWriter = object : BleGattTransportFrameWriter {
                override fun write(
                    frame: BleGattTransportFrame,
                    listener: BleGattTransportFrameWriter.Listener
                ) {
                    listener.onWriteResult(BleGattTransportFrameWriteResult.Accepted)
                }
            }
        )

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
    fun sendEmitsLocalTraceAfterAllChunksAreAccepted() {
        val traces = mutableListOf<BleTransportLocalSendTrace>()
        val plan = OutgoingBleTransportSendPlanBuilder.build(
            messageId = "android-sender-6",
            targetPeerId = "peer-f",
            encryptedEnvelopeBytes = ByteArray(BleGattTransportChunk.MAX_PAYLOAD_SIZE * 2 + 5) {
                index -> (index + 11).toByte()
            }
        )
        val sender = AndroidBleTransportSender(
            frameWriter = object : BleGattTransportFrameWriter {
                override fun write(
                    frame: BleGattTransportFrame,
                    listener: BleGattTransportFrameWriter.Listener
                ) {
                    listener.onWriteResult(BleGattTransportFrameWriteResult.Accepted)
                }
            },
            onLocalSendTraceReady = { trace -> traces += trace }
        )

        sender.send(
            plan = plan,
            listener = object : BleTransportSender.Listener {
                override fun onSendResult(result: BleTransportSendResult) {
                }
            }
        )

        assertEquals(1, traces.size)
        val trace = traces.single()
        assertEquals(plan.messageId, trace.messageId)
        assertEquals(plan.targetPeerId, trace.targetPeerId)
        assertEquals(plan.groupId, trace.groupId)
        assertEquals(plan.metrics().encodedPayloadByteCount, trace.encodedPayloadByteCount)
        assertEquals(plan.framesInSendOrder().size, trace.chunkCount)
        assertEquals(trace.chunkCount, trace.chunksQueued)
        assertEquals(trace.chunkCount, trace.chunksWriteAttempted)
        assertEquals("QueuedLocally", trace.lastLocalWriteResult)
    }

    @Test
    fun sendWaitsForPreviousWriteCallbackBeforeAttemptingNextChunk() {
        val writeListeners = mutableListOf<BleGattTransportFrameWriter.Listener>()
        val writtenFrames = mutableListOf<BleGattTransportFrame>()
        val results = mutableListOf<BleTransportSendResult>()
        val plan = OutgoingBleTransportSendPlanBuilder.build(
            messageId = "android-sender-7",
            targetPeerId = "peer-g",
            encryptedEnvelopeBytes = ByteArray(BleGattTransportChunk.MAX_PAYLOAD_SIZE * 2 + 4) {
                index -> (index + 13).toByte()
            }
        )
        val sender = AndroidBleTransportSender(
            frameWriter = object : BleGattTransportFrameWriter {
                override fun write(
                    frame: BleGattTransportFrame,
                    listener: BleGattTransportFrameWriter.Listener
                ) {
                    writtenFrames += frame
                    writeListeners += listener
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

        assertEquals(1, writtenFrames.size)
        assertTrue(results.isEmpty())

        writeListeners.removeAt(0).onWriteResult(BleGattTransportFrameWriteResult.Accepted)
        assertEquals(2, writtenFrames.size)
        assertTrue(results.isEmpty())

        writeListeners.removeAt(0).onWriteResult(BleGattTransportFrameWriteResult.Accepted)
        assertEquals(3, writtenFrames.size)
        assertTrue(results.isEmpty())

        writeListeners.removeAt(0).onWriteResult(BleGattTransportFrameWriteResult.Accepted)
        assertEquals(listOf(BleTransportSendResult.QueuedLocally), results)
    }
}
