package gr.hua.aurora.protocol

object NoOpIncomingSessionMaterialProvider : IncomingSessionMaterialProvider {
    override fun decryptionMaterialFor(
        envelope: EncryptedMessageEnvelope
    ): IncomingSessionMaterialLookupResult {
        return IncomingSessionMaterialLookupResult.MaterialUnavailable(
            reason = "Incoming session material is unavailable for the sender."
        )
    }
}
