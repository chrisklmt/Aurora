package gr.hua.aurora.protocol

import java.security.MessageDigest

private const val canonicalPeerIdSizeBytes = 8

object PeerSessionPeerId {
    fun sanitize(
        peerId: String
    ): String {
        val sanitizedPeerId = peerId.trim()
        require(sanitizedPeerId.isNotEmpty()) {
            "Peer session peerId must not be blank."
        }
        return sanitizedPeerId
    }

    fun deriveFromPublicKey(
        publicKeyBytes: ByteArray
    ): String {
        require(publicKeyBytes.isNotEmpty()) {
            "Peer session public key bytes must not be empty."
        }

        return MessageDigest.getInstance("SHA-256")
            .digest(publicKeyBytes.copyOf())
            .copyOf(canonicalPeerIdSizeBytes)
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }
    }
}
