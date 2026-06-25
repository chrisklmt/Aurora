package gr.hua.aurora.protocol

fun interface IncomingSessionMaterialProvider {
    fun decryptionMaterialFor(
        envelope: EncryptedMessageEnvelope
    ): IncomingSessionMaterialLookupResult
}

sealed interface IncomingSessionMaterialLookupResult {
    data class Found(
        val material: IncomingMessageReceiveDecryptionMaterial
    ) : IncomingSessionMaterialLookupResult

    data class MaterialUnavailable(
        val reason: String
    ) : IncomingSessionMaterialLookupResult {
        init {
            require(reason.isNotBlank()) {
                "Incoming session material unavailable reason must not be blank."
            }
        }
    }

    data class UnsupportedSender(
        val reason: String
    ) : IncomingSessionMaterialLookupResult {
        init {
            require(reason.isNotBlank()) {
                "Incoming unsupported sender reason must not be blank."
            }
        }
    }

    data class InvalidIdentity(
        val reason: String
    ) : IncomingSessionMaterialLookupResult {
        init {
            require(reason.isNotBlank()) {
                "Incoming invalid identity reason must not be blank."
            }
        }
    }
}
