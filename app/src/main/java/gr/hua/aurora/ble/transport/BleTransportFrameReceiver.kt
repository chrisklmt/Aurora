package gr.hua.aurora.ble.transport

import gr.hua.aurora.transport.processing.IncomingTransportFrameProcessingResult
import gr.hua.aurora.transport.processing.IncomingTransportFrameProcessingSuccessResult

class BleTransportFrameReceiver(
    private val processFrames: (Collection<BleGattTransportFrame>) -> IncomingTransportFrameProcessingResult,
    private val buffer: BleTransportReceiveBuffer = BleTransportReceiveBuffer()
) {
    fun receive(
        frame: BleGattTransportFrame
    ): BleTransportReceiveResult {
        return receive(
            BleTransportIncomingFrame(frame = frame)
        )
    }

    fun receive(
        frame: BleTransportIncomingFrame
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
                            sourceDeviceAddress = bufferResult.sourceDeviceAddress,
                            processingResult = processingResult
                        )
                    }
                    is IncomingTransportFrameProcessingResult.ReceiveFailed -> {
                        BleTransportReceiveResult.ProcessorFailed(
                            groupId = bufferResult.groupId,
                            sourceDeviceAddress = bufferResult.sourceDeviceAddress,
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
