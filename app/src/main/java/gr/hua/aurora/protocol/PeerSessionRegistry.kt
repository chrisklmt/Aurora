package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import gr.hua.aurora.model.OutgoingChatMessage
import java.util.Base64

class EstablishedPeerSession(
    val peerId: String,
    peerPublicKey: ByteArray,
    val outgoingMaterial: OutgoingMessageSendEncryptionMaterial,
    val incomingMaterial: IncomingMessageReceiveDecryptionMaterial
) {
    private val storedPeerPublicKey = peerPublicKey.copyOf()

    init {
        require(peerId.isNotBlank()) {
            "Established peer session peerId must not be blank."
        }
        Sec1PublicKeyEncoding.decodeUncompressed(storedPeerPublicKey)
    }

    fun peerPublicKeyToByteArray(): ByteArray {
        return storedPeerPublicKey.copyOf()
    }
}

sealed interface OutgoingSessionMaterialLookupResult {
    data class Found(
        val material: OutgoingMessageSendEncryptionMaterial
    ) : OutgoingSessionMaterialLookupResult

    data class MaterialUnavailable(
        val reason: String
    ) : OutgoingSessionMaterialLookupResult {
        init {
            require(reason.isNotBlank()) {
                "Outgoing session material unavailable reason must not be blank."
            }
        }
    }
}

data class PeerSessionRegistryDiagnostics(
    val establishedPeerIds: List<String>,
    val canonicalPeerIdByAlias: Map<String, String>
)

fun PeerSessionRegistryDiagnostics.canonicalPeerIdFor(
    peerId: String?
): String? {
    val sanitizedPeerId = peerId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        establishedPeerIds.contains(sanitizedPeerId) -> sanitizedPeerId
        else -> canonicalPeerIdByAlias[sanitizedPeerId]
    }
}

fun PeerSessionRegistryDiagnostics.hasSessionForPeer(
    peerId: String?
): Boolean {
    return canonicalPeerIdFor(peerId) != null
}

class PeerSessionRegistry : OutgoingSessionMaterialProvider, IncomingSessionMaterialProvider {
    private val sessionsByPeerId = LinkedHashMap<String, EstablishedPeerSession>()
    private val peerIdByPublicKeyToken = LinkedHashMap<String, String>()
    private val canonicalPeerIdByAlias = LinkedHashMap<String, String>()

    fun putSession(session: EstablishedPeerSession) {
        val newPublicKeyToken = publicKeyToken(session.peerPublicKeyToByteArray())
        val canonicalPeerId = PeerSessionPeerId.sanitize(session.peerId)

        removeSessionForPeerId(canonicalPeerId)

        val replacedPeerId = peerIdByPublicKeyToken[newPublicKeyToken]
        if (replacedPeerId != null && replacedPeerId != canonicalPeerId) {
            removeSessionForPeerId(replacedPeerId)
        }

        sessionsByPeerId[canonicalPeerId] = session
        peerIdByPublicKeyToken[newPublicKeyToken] = canonicalPeerId
    }

    fun putPeerIdAlias(
        aliasPeerId: String,
        canonicalPeerId: String
    ): Boolean {
        val sanitizedAliasPeerId = PeerSessionPeerId.sanitize(aliasPeerId)
        val sanitizedCanonicalPeerId = PeerSessionPeerId.sanitize(canonicalPeerId)
        if (sanitizedAliasPeerId == sanitizedCanonicalPeerId) {
            return false
        }
        if (!sessionsByPeerId.containsKey(sanitizedCanonicalPeerId)) {
            return false
        }
        if (sessionsByPeerId.containsKey(sanitizedAliasPeerId)) {
            return false
        }

        canonicalPeerIdByAlias[sanitizedAliasPeerId] = sanitizedCanonicalPeerId
        return true
    }

    fun lookupOutgoingMaterial(
        message: OutgoingChatMessage
    ): OutgoingSessionMaterialLookupResult {
        val draft = try {
            OutgoingMessageFrameBuilder.build(message)
        } catch (error: IllegalArgumentException) {
            return OutgoingSessionMaterialLookupResult.MaterialUnavailable(
                reason = error.message ?: "Outgoing session material is unavailable for the message."
            )
        }

        if (draft.recipientId == null) {
            return OutgoingSessionMaterialLookupResult.MaterialUnavailable(
                reason = "Outgoing session material is unavailable for the global thread."
            )
        }
        return lookupOutgoingMaterialForPeer(draft.recipientId)
    }

    fun lookupOutgoingMaterialForPeer(
        peerId: String
    ): OutgoingSessionMaterialLookupResult {
        val sanitizedPeerId = PeerSessionPeerId.sanitize(peerId)
        val resolvedPeerId = resolvePeerId(sanitizedPeerId) ?: sanitizedPeerId
        val session = sessionsByPeerId[resolvedPeerId]
            ?: return OutgoingSessionMaterialLookupResult.MaterialUnavailable(
                reason = "Outgoing session material is unavailable for peer $sanitizedPeerId."
            )

        return OutgoingSessionMaterialLookupResult.Found(
            material = session.outgoingMaterial
        )
    }

    override fun encryptionMaterialFor(
        message: OutgoingChatMessage
    ): OutgoingMessageSendEncryptionMaterial? {
        return when (val lookupResult = lookupOutgoingMaterial(message)) {
            is OutgoingSessionMaterialLookupResult.Found -> lookupResult.material
            is OutgoingSessionMaterialLookupResult.MaterialUnavailable -> null
        }
    }

    override fun encryptionMaterialForTarget(
        peerId: String
    ): OutgoingMessageSendEncryptionMaterial? {
        return when (val lookupResult = lookupOutgoingMaterialForPeer(peerId)) {
            is OutgoingSessionMaterialLookupResult.Found -> lookupResult.material
            is OutgoingSessionMaterialLookupResult.MaterialUnavailable -> null
        }
    }

    fun lookupIncomingMaterial(
        envelope: EncryptedMessageEnvelope
    ): IncomingSessionMaterialLookupResult {
        val senderPublicKeyToken = try {
            publicKeyToken(envelope.senderPublicKey)
        } catch (_: IllegalArgumentException) {
            return IncomingSessionMaterialLookupResult.InvalidIdentity(
                reason = "Incoming sender identity is invalid."
            )
        }

        val peerId = peerIdByPublicKeyToken[senderPublicKeyToken]
            ?: return IncomingSessionMaterialLookupResult.MaterialUnavailable(
                reason = "Incoming session material is unavailable for the sender."
            )
        val session = sessionsByPeerId[peerId]
            ?: return IncomingSessionMaterialLookupResult.MaterialUnavailable(
                reason = "Incoming session material is unavailable for the sender."
            )

        return IncomingSessionMaterialLookupResult.Found(
            material = session.incomingMaterial
        )
    }

    override fun decryptionMaterialFor(
        envelope: EncryptedMessageEnvelope
    ): IncomingSessionMaterialLookupResult {
        return lookupIncomingMaterial(envelope)
    }

    fun clearPeer(
        peerId: String
    ): Boolean {
        val sanitizedPeerId = PeerSessionPeerId.sanitize(peerId)
        val resolvedPeerId = resolvePeerId(sanitizedPeerId) ?: sanitizedPeerId
        return removeSessionForPeerId(resolvedPeerId) != null
    }

    fun clearAll() {
        sessionsByPeerId.clear()
        peerIdByPublicKeyToken.clear()
        canonicalPeerIdByAlias.clear()
    }

    fun hasSessionForPeer(
        peerId: String?
    ): Boolean {
        return canonicalPeerIdFor(peerId) != null
    }

    fun canonicalPeerIdFor(
        peerId: String?
    ): String? {
        val sanitizedPeerId = peerId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return resolvePeerId(sanitizedPeerId)
    }

    fun diagnosticsSnapshot(): PeerSessionRegistryDiagnostics {
        return PeerSessionRegistryDiagnostics(
            establishedPeerIds = sessionsByPeerId.keys.toList(),
            canonicalPeerIdByAlias = canonicalPeerIdByAlias.toMap()
        )
    }

    private fun removeSessionForPeerId(
        peerId: String
    ): EstablishedPeerSession? {
        val removedSession = sessionsByPeerId.remove(peerId) ?: return null
        peerIdByPublicKeyToken.remove(
            publicKeyToken(removedSession.peerPublicKeyToByteArray())
        )
        canonicalPeerIdByAlias.entries.removeAll { it.value == peerId }
        return removedSession
    }

    private fun resolvePeerId(
        peerId: String
    ): String? {
        return when {
            sessionsByPeerId.containsKey(peerId) -> peerId
            else -> canonicalPeerIdByAlias[peerId]
        }
    }

    private fun publicKeyToken(
        publicKey: ByteArray
    ): String {
        val validatedKey = publicKey.copyOf()
        Sec1PublicKeyEncoding.decodeUncompressed(validatedKey)
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(validatedKey)
    }
}
