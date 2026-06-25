package gr.hua.aurora.protocol

import gr.hua.aurora.model.OutgoingChatMessage

interface OutgoingSessionMaterialProvider {
    fun encryptionMaterialFor(
        message: OutgoingChatMessage
    ): OutgoingMessageSendEncryptionMaterial?

    fun encryptionMaterialForTarget(
        peerId: String
    ): OutgoingMessageSendEncryptionMaterial? {
        require(peerId.isNotBlank()) {
            "Outgoing session material target peerId must not be blank."
        }

        return null
    }
}
