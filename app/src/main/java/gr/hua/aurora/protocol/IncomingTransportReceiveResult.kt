package gr.hua.aurora.protocol

sealed interface IncomingTransportReceiveResult {
    data class Received(
        val message: IncomingTransportMessage
    ) : IncomingTransportReceiveResult

    data class RelayOnlyEncrypted(
        val envelope: EncryptedMessageEnvelope
    ) : IncomingTransportReceiveResult

    data class IncompleteChunks(
        val reason: String
    ) : IncomingTransportReceiveResult {
        init {
            require(reason.isNotBlank()) {
                "Incoming transport chunk failure reason must not be blank."
            }
        }
    }

    data class InvalidEnvelope(
        val reason: String
    ) : IncomingTransportReceiveResult {
        init {
            require(reason.isNotBlank()) {
                "Incoming envelope failure reason must not be blank."
            }
        }
    }

    data class SessionMaterialUnavailable(
        val reason: String
    ) : IncomingTransportReceiveResult {
        init {
            require(reason.isNotBlank()) {
                "Incoming session material unavailable reason must not be blank."
            }
        }
    }

    data class UnsupportedSender(
        val reason: String
    ) : IncomingTransportReceiveResult {
        init {
            require(reason.isNotBlank()) {
                "Incoming unsupported sender reason must not be blank."
            }
        }
    }

    data class InvalidSenderIdentity(
        val reason: String
    ) : IncomingTransportReceiveResult {
        init {
            require(reason.isNotBlank()) {
                "Incoming invalid sender identity reason must not be blank."
            }
        }
    }

    data class DecryptFailed(
        val reason: String
    ) : IncomingTransportReceiveResult {
        init {
            require(reason.isNotBlank()) {
                "Incoming decrypt failure reason must not be blank."
            }
        }
    }

    data class InvalidFrame(
        val reason: String
    ) : IncomingTransportReceiveResult {
        init {
            require(reason.isNotBlank()) {
                "Incoming frame failure reason must not be blank."
            }
        }
    }
}
