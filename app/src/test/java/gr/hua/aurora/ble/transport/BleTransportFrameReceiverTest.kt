package gr.hua.aurora.ble.transport

import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.protocol.IncomingTransportMessage
import gr.hua.aurora.protocol.IncomingTransportReceiveResult
import gr.hua.aurora.protocol.MessageFrame
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.state.IncomingMessageIngestionResult
import gr.hua.aurora.state.IncomingTransportFrameProcessingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleTransportFrameReceiverTest {
    @Test
    fun oneCompleteOneFrameMessageTriggersProcessor() {
        val expectedProcessingResult = receivedProcessingResult(
            messageId = "incoming-one-frame"
        )
        var processorCalls = 0
        var processedFrames: Collection<BleGattTransportFrame> = emptyList()
        val receiver = BleTransportFrameReceiver(
            processFrames = { frames ->
                processorCalls += 1
                processedFrames = frames
                expectedProcessingResult
            }
        )
        val frame = singleChunkFrame(groupId = 0x4101, payloadByte = 0x21)

        val result = receiver.receive(frame)

        assertTrue(result is BleTransportReceiveResult.Processed)
        assertEquals(1, processorCalls)
        assertEquals(listOf(frame), processedFrames.toList())
        assertEquals(0, receiver.activeGroupCount())
        assertEquals(
            expectedProcessingResult,
            (result as BleTransportReceiveResult.Processed).processingResult
        )
    }

    @Test
    fun multiFrameMessageTriggersProcessorOnlyAfterFinalChunk() {
        var processorCalls = 0
        val receiver = BleTransportFrameReceiver(
            processFrames = {
                processorCalls += 1
                receivedProcessingResult(messageId = "incoming-multi-frame")
            }
        )
        val frames = multiChunkFrames(
            groupId = 0x4102,
            payloadSize = BleGattTransportChunk.MAX_PAYLOAD_SIZE * 2 + 2
        )

        val firstResult = receiver.receive(frames[0])
        val secondResult = receiver.receive(frames[1])
        val thirdResult = receiver.receive(frames[2])

        assertTrue(firstResult is BleTransportReceiveResult.Buffered)
        assertTrue(secondResult is BleTransportReceiveResult.Buffered)
        assertTrue(thirdResult is BleTransportReceiveResult.Processed)
        assertEquals(1, processorCalls)
        assertEquals(0, receiver.activeGroupCount())
    }

    @Test
    fun outOfOrderChunksWork() {
        var processorCalls = 0
        var processedFrames: Collection<BleGattTransportFrame> = emptyList()
        val receiver = BleTransportFrameReceiver(
            processFrames = { frames ->
                processorCalls += 1
                processedFrames = frames
                receivedProcessingResult(messageId = "incoming-out-of-order")
            }
        )
        val frames = multiChunkFrames(
            groupId = 0x4103,
            payloadSize = BleGattTransportChunk.MAX_PAYLOAD_SIZE * 2 + 1
        )

        val firstResult = receiver.receive(frames[2])
        val secondResult = receiver.receive(frames[0])
        val thirdResult = receiver.receive(frames[1])

        assertTrue(firstResult is BleTransportReceiveResult.Buffered)
        assertTrue(secondResult is BleTransportReceiveResult.Buffered)
        assertTrue(thirdResult is BleTransportReceiveResult.Processed)
        assertEquals(1, processorCalls)
        assertEquals(frames, processedFrames.toList())
    }

    @Test
    fun duplicateChunkRejected() {
        var processorCalls = 0
        val receiver = BleTransportFrameReceiver(
            processFrames = {
                processorCalls += 1
                receivedProcessingResult(messageId = "incoming-duplicate")
            }
        )
        val frames = multiChunkFrames(
            groupId = 0x4104,
            payloadSize = BleGattTransportChunk.MAX_PAYLOAD_SIZE + 1
        )

        val firstResult = receiver.receive(frames[0])
        val duplicateResult = receiver.receive(frames[0])

        assertTrue(firstResult is BleTransportReceiveResult.Buffered)
        assertTrue(duplicateResult is BleTransportReceiveResult.DuplicateChunk)
        assertEquals(0x4104, (duplicateResult as BleTransportReceiveResult.DuplicateChunk).groupId)
        assertEquals(0, duplicateResult.chunkIndex)
        assertEquals(0, processorCalls)
        assertEquals(1, receiver.activeGroupCount())
    }

    @Test
    fun invalidChunkRejected() {
        val receiver = BleTransportFrameReceiver(
            processFrames = {
                receivedProcessingResult(messageId = "incoming-invalid")
            }
        )
        val invalidFrame = checkNotNull(
            BleGattTransportFrame.create(
                body = byteArrayOf()
            )
        )

        val result = receiver.receive(invalidFrame)

        assertTrue(result is BleTransportReceiveResult.InvalidChunk)
        assertEquals(0, receiver.activeGroupCount())
    }

    @Test
    fun completedGroupIsClearedAfterProcessing() {
        val receiver = BleTransportFrameReceiver(
            processFrames = {
                receivedProcessingResult(messageId = "incoming-clear")
            }
        )
        val firstMessage = singleChunkFrame(groupId = 0x4105, payloadByte = 0x31)
        val secondMessage = singleChunkFrame(groupId = 0x4105, payloadByte = 0x32)

        val firstResult = receiver.receive(firstMessage)
        val secondResult = receiver.receive(secondMessage)

        assertTrue(firstResult is BleTransportReceiveResult.Processed)
        assertTrue(secondResult is BleTransportReceiveResult.Processed)
        assertEquals(0, receiver.activeGroupCount())
    }

    @Test
    fun processorReceiveFailureIsSurfaced() {
        val receiver = BleTransportFrameReceiver(
            processFrames = {
                IncomingTransportFrameProcessingResult.ReceiveFailed(
                    receiveResult = IncomingTransportReceiveResult.InvalidEnvelope(
                        reason = "bad envelope"
                    )
                )
            }
        )
        val frame = singleChunkFrame(groupId = 0x4106, payloadByte = 0x41)

        val result = receiver.receive(frame)

        assertTrue(result is BleTransportReceiveResult.ProcessorFailed)
        val failedResult = result as BleTransportReceiveResult.ProcessorFailed
        assertEquals(0x4106, failedResult.groupId)
        assertTrue(failedResult.processingResult.receiveResult is IncomingTransportReceiveResult.InvalidEnvelope)
        assertEquals(0, receiver.activeGroupCount())
    }

    @Test
    fun maxGroupGuardPreventsUnboundedGrowth() {
        val receiver = BleTransportFrameReceiver(
            processFrames = {
                receivedProcessingResult(messageId = "incoming-guard")
            },
            buffer = BleTransportReceiveBuffer(
                maxGroups = 1,
                maxFramesPerGroup = 4
            )
        )
        val firstGroupFrame = groupFrame(
            groupId = 0x4107,
            chunkIndex = 0,
            totalChunks = 2,
            payloadByte = 0x51
        )
        val secondGroupFrame = groupFrame(
            groupId = 0x4108,
            chunkIndex = 0,
            totalChunks = 2,
            payloadByte = 0x52
        )

        val firstResult = receiver.receive(firstGroupFrame)
        val overflowResult = receiver.receive(secondGroupFrame)

        assertTrue(firstResult is BleTransportReceiveResult.Buffered)
        assertTrue(overflowResult is BleTransportReceiveResult.BufferOverflow)
        assertEquals(1, receiver.activeGroupCount())
    }

    @Test
    fun groupLargerThanLegacyLimitIsBufferedAndProcessedWhenWithinSharedLimit() {
        var processorCalls = 0
        val receiver = BleTransportFrameReceiver(
            processFrames = {
                processorCalls += 1
                receivedProcessingResult(messageId = "incoming-large-group")
            }
        )
        val frames = multiChunkFrames(
            groupId = 0x4109,
            payloadSize = BleGattTransportChunk.MAX_PAYLOAD_SIZE * 65
        )

        var lastResult: BleTransportReceiveResult? = null
        frames.forEach { frame ->
            lastResult = receiver.receive(frame)
        }

        assertEquals(65, frames.size)
        assertEquals(1, processorCalls)
        assertTrue(lastResult is BleTransportReceiveResult.Processed)
        assertEquals(0, receiver.activeGroupCount())
    }

    private fun singleChunkFrame(
        groupId: Int,
        payloadByte: Int
    ): BleGattTransportFrame {
        return groupFrame(
            groupId = groupId,
            chunkIndex = 0,
            totalChunks = 1,
            payloadByte = payloadByte
        )
    }

    private fun groupFrame(
        groupId: Int,
        chunkIndex: Int,
        totalChunks: Int,
        payloadByte: Int
    ): BleGattTransportFrame {
        return checkNotNull(
            BleGattTransportChunk.create(
                groupId = groupId,
                chunkIndex = chunkIndex,
                totalChunks = totalChunks,
                payload = byteArrayOf(payloadByte.toByte())
            )
        ).toFrame()
            ?: error("Chunk should produce a transport frame.")
    }

    private fun multiChunkFrames(
        groupId: Int,
        payloadSize: Int
    ): List<BleGattTransportFrame> {
        val bytes = ByteArray(payloadSize) { index ->
            (index and 0xFF).toByte()
        }

        return BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = bytes,
            groupId = groupId
        )
    }

    private fun receivedProcessingResult(
        messageId: String
    ): IncomingTransportFrameProcessingResult.Received {
        val message = ChatMessage(
            id = messageId,
            threadId = "global",
            senderId = "peer",
            senderName = "peer",
            text = "hello",
            createdAtMillis = 1_234L,
            status = MessageStatus.RECEIVED,
            isOutgoing = false
        )

        return IncomingTransportFrameProcessingResult.Received(
            message = IncomingTransportMessage(
                frame = MessageFrame(
                    id = messageId,
                    type = MessageFrameType.GLOBAL_TEXT,
                    senderId = "peer",
                    createdAtMillis = 1_234L,
                    payload = "hello"
                ),
                senderPublicKey = byteArrayOf(1, 2, 3, 4)
            ),
            ingestionResult = IncomingMessageIngestionResult.Appended(
                message = message
            )
        )
    }
}
