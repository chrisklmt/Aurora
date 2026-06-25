package gr.hua.aurora.ble.transport

import gr.hua.aurora.state.IncomingTransportFrameProcessingResult
import gr.hua.aurora.state.IncomingTransportFrameProcessingSuccessResult

class BleTransportFrameReceiver(
    private val processFrames: (Collection<BleGattTransportFrame>) -> IncomingTransportFrameProcessingResult,
    private val buffer: BleTransportReceiveBuffer = BleTransportReceiveBuffer()
) {
    fun receive(
        frame: BleGattTransportFrame
    ): BleTransportReceiveResult {
        return when (val bufferResult = buffer.buffer(frame)) {
            is BleTransportReceiveBuffer.BufferResult.Buffered -> {
                BleTransportReceiveResult.Buffered(
                    groupId = bufferResult.groupId,
                    receivedChunks = bufferResult.receivedChunks,
                    expectedChunks = bufferResult.expectedChunks
                )
            }
            is BleTransportReceiveBuffer.BufferResult.Complete -> {
                when (val processingResult = processFrames(bufferResult.frames)) {
                    is IncomingTransportFrameProcessingSuccessResult -> {
                        BleTransportReceiveResult.Processed(
                            groupId = bufferResult.groupId,
                            processingResult = processingResult
                        )
                    }
                    is IncomingTransportFrameProcessingResult.ReceiveFailed -> {
                        BleTransportReceiveResult.ProcessorFailed(
                            groupId = bufferResult.groupId,
                            processingResult = processingResult
                        )
                    }
                }
            }
            is BleTransportReceiveBuffer.BufferResult.DuplicateChunk -> {
                BleTransportReceiveResult.DuplicateChunk(
                    groupId = bufferResult.groupId,
                    chunkIndex = bufferResult.chunkIndex
                )
            }
            is BleTransportReceiveBuffer.BufferResult.InvalidChunk -> {
                BleTransportReceiveResult.InvalidChunk(
                    reason = bufferResult.reason
                )
            }
            is BleTransportReceiveBuffer.BufferResult.BufferOverflow -> {
                BleTransportReceiveResult.BufferOverflow(
                    reason = bufferResult.reason
                )
            }
        }
    }

    fun activeGroupCount(): Int {
        return buffer.activeGroupCount()
    }

    fun clear() {
        buffer.clear()
    }
}
