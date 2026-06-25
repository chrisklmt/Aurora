package gr.hua.aurora.ble.transport

import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.protocol.IncomingTransportMessage
import gr.hua.aurora.protocol.MessageFrame
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.state.IncomingMessageIngestionResult
import gr.hua.aurora.state.IncomingTransportFrameProcessingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleTransportFrameBridgeTest {
    @Test
    fun listenerReceivesDecodedFrameAndForwardsToReceiver() {
        var processorCalls = 0
        val receiver = BleTransportFrameReceiver(
            processFrames = {
                processorCalls += 1
                receivedProcessingResult(messageId = "bridge-1")
            }
        )
        val results = mutableListOf<BleTransportReceiveResult>()
        val bridge = BleTransportFrameBridge(
            receiver = receiver,
            dispatch = { runnable -> runnable() },
            onReceiveResult = results::add
        )
        val frame = checkNotNull(
            BleGattTransportChunk.create(
                groupId = 0x4201,
                chunkIndex = 0,
                totalChunks = 1,
                payload = byteArrayOf(0x33)
            )
        ).toFrame()
            ?: error("Chunk should produce a transport frame.")

        bridge.onFrameReceived(frame)

        assertEquals(1, processorCalls)
        assertEquals(1, results.size)
        assertTrue(results.single() is BleTransportReceiveResult.Processed)
    }

    @Test
    fun clearResetsBufferedGroups() {
        val receiver = BleTransportFrameReceiver(
            processFrames = {
                receivedProcessingResult(messageId = "bridge-clear")
            }
        )
        val bridge = BleTransportFrameBridge(
            receiver = receiver,
            dispatch = { runnable -> runnable() }
        )
        val partialFrame = checkNotNull(
            BleGattTransportChunk.create(
                groupId = 0x4202,
                chunkIndex = 0,
                totalChunks = 2,
                payload = byteArrayOf(0x34)
            )
        ).toFrame()
            ?: error("Chunk should produce a transport frame.")

        bridge.onFrameReceived(partialFrame)
        assertEquals(1, receiver.activeGroupCount())

        bridge.clear()

        assertEquals(0, receiver.activeGroupCount())
    }

    private fun receivedProcessingResult(
        messageId: String
    ): IncomingTransportFrameProcessingResult.Received {
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
                message = ChatMessage(
                    id = messageId,
                    threadId = "global",
                    senderId = "peer",
                    senderName = "peer",
                    text = "hello",
                    createdAtMillis = 1_234L,
                    status = MessageStatus.RECEIVED,
                    isOutgoing = false
                )
            )
        )
    }
}
