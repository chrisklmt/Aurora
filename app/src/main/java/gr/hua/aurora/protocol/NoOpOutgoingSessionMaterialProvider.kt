package gr.hua.aurora.protocol

import gr.hua.aurora.model.OutgoingChatMessage

object NoOpOutgoingSessionMaterialProvider : OutgoingSessionMaterialProvider {
    override fun encryptionMaterialFor(
        message: OutgoingChatMessage
    ): OutgoingMessageSendEncryptionMaterial? {
        return null
    }

    override fun encryptionMaterialForTarget(
        peerId: String
    ): OutgoingMessageSendEncryptionMaterial? {
        return null
    }
}
