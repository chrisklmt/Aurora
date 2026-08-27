package gr.hua.aurora.ble.transport

import gr.hua.aurora.transport.processing.IncomingTransportFrameProcessingResult
import gr.hua.aurora.transport.processing.IncomingTransportFrameProcessingSuccessResult

sealed interface BleTransportReceiveResult {
    data class Buffered(
        val groupId: Int,
        val receivedChunks: Int,
        val expectedChunks: Int
    ) : BleTransportReceiveResult

    data class Processed(
        val groupId: Int,
        val sourceDeviceAddress: String? = null,
        val processingResult: IncomingTransportFrameProcessingSuccessResult
    ) : BleTransportReceiveResult

    data class DuplicateChunk(
        val groupId: Int,
        val chunkIndex: Int
    ) : BleTransportReceiveResult

    data class InvalidChunk(
        val reason: String,
        val groupId: Int? = null,
        val receivedChunks: Int? = null,
        val expectedChunks: Int? = null
    ) : BleTransportReceiveResult {
        init {
            require(reason.isNotBlank()) {
                "Transport receive invalid chunk reason must not be blank."
            }
            require(groupId == null || groupId in 0..0xFFFF) {
                "Transport receive invalid chunk groupId must be between 0 and 65535 when present."
            }
            require(receivedChunks == null || receivedChunks >= 0) {
                "Transport receive invalid chunk receivedChunks must be non-negative when present."
            }
            require(expectedChunks == null || expectedChunks >= 0) {
                "Transport receive invalid chunk expectedChunks must be non-negative when present."
            }
        }
    }

    data class ProcessorFailed(
        val groupId: Int,
        val sourceDeviceAddress: String? = null,
        val processingResult: IncomingTransportFrameProcessingResult.ReceiveFailed
    ) : BleTransportReceiveResult

    data class BufferOverflow(
        val reason: String,
        val groupId: Int? = null,
        val receivedChunks: Int? = null,
        val expectedChunks: Int? = null
    ) : BleTransportReceiveResult {
        init {
            require(reason.isNotBlank()) {
                "Transport receive buffer overflow reason must not be blank."
            }
            require(groupId == null || groupId in 0..0xFFFF) {
                "Transport receive buffer overflow groupId must be between 0 and 65535 when present."
            }
            require(receivedChunks == null || receivedChunks >= 0) {
                "Transport receive buffer overflow receivedChunks must be non-negative when present."
            }
            require(expectedChunks == null || expectedChunks >= 0) {
                "Transport receive buffer overflow expectedChunks must be non-negative when present."
            }
        }
    }
}
