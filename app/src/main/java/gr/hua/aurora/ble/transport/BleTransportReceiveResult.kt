package gr.hua.aurora.ble.transport

import gr.hua.aurora.state.IncomingTransportFrameProcessingResult
import gr.hua.aurora.state.IncomingTransportFrameProcessingSuccessResult

sealed interface BleTransportReceiveResult {
    data class Buffered(
        val groupId: Int,
        val receivedChunks: Int,
        val expectedChunks: Int
    ) : BleTransportReceiveResult

    data class Processed(
        val groupId: Int,
        val processingResult: IncomingTransportFrameProcessingSuccessResult
    ) : BleTransportReceiveResult

    data class DuplicateChunk(
        val groupId: Int,
        val chunkIndex: Int
    ) : BleTransportReceiveResult

    data class InvalidChunk(
        val reason: String
    ) : BleTransportReceiveResult {
        init {
            require(reason.isNotBlank()) {
                "Transport receive invalid chunk reason must not be blank."
            }
        }
    }

    data class ProcessorFailed(
        val groupId: Int,
        val processingResult: IncomingTransportFrameProcessingResult.ReceiveFailed
    ) : BleTransportReceiveResult

    data class BufferOverflow(
        val reason: String
    ) : BleTransportReceiveResult {
        init {
            require(reason.isNotBlank()) {
                "Transport receive buffer overflow reason must not be blank."
            }
        }
    }
}
