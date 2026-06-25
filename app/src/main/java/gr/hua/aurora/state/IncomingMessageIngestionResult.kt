package gr.hua.aurora.state

import gr.hua.aurora.model.ChatMessage

sealed interface IncomingMessageIngestionResult {
    data class Appended(
        val message: ChatMessage
    ) : IncomingMessageIngestionResult

    data class Duplicate(
        val messageId: String
    ) : IncomingMessageIngestionResult

    data class UnsupportedThread(
        val reason: String
    ) : IncomingMessageIngestionResult {
        init {
            require(reason.isNotBlank()) {
                "Incoming message unsupported thread reason must not be blank."
            }
        }
    }

    data class UnsupportedType(
        val reason: String
    ) : IncomingMessageIngestionResult {
        init {
            require(reason.isNotBlank()) {
                "Incoming message unsupported type reason must not be blank."
            }
        }
    }
}
