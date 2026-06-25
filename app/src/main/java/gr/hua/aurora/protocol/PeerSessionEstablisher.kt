package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.EcdhKeyAgreement
import gr.hua.aurora.crypto.HkdfSessionKeyDerivation
import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey

class LocalPeerSessionIdentityMaterial(
    publicKeyBytes: ByteArray,
    val privateKey: PrivateKey
) {
    private val storedPublicKeyBytes = publicKeyBytes.copyOf()

    init {
        Sec1PublicKeyEncoding.decodeUncompressed(storedPublicKeyBytes)
    }

    fun publicKeyBytes(): ByteArray {
        return storedPublicKeyBytes.copyOf()
    }
}

sealed interface PeerSessionEstablishmentResult {
    data class Established(
        val session: EstablishedPeerSession
    ) : PeerSessionEstablishmentResult

    data class InvalidRemotePublicKey(
        val reason: String
    ) : PeerSessionEstablishmentResult {
        init {
            require(reason.isNotBlank()) {
                "Peer session invalid remote public key reason must not be blank."
            }
        }
    }

    data class SelfPeer(
        val reason: String
    ) : PeerSessionEstablishmentResult {
        init {
            require(reason.isNotBlank()) {
                "Peer session self peer reason must not be blank."
            }
        }
    }

    data class KeyAgreementFailed(
        val reason: String
    ) : PeerSessionEstablishmentResult {
        init {
            require(reason.isNotBlank()) {
                "Peer session key agreement failure reason must not be blank."
            }
        }
    }

    data class KeyDerivationFailed(
        val reason: String
    ) : PeerSessionEstablishmentResult {
        init {
            require(reason.isNotBlank()) {
                "Peer session key derivation failure reason must not be blank."
            }
        }
    }
}

object PeerSessionEstablisher {
    fun establish(
        localIdentity: LocalPeerSessionIdentityMaterial,
        remotePeerId: String,
        remotePeerPublicKeyBytes: ByteArray,
        deriveSharedSecret: (
            PrivateKey,
            ECPublicKey
        ) -> ByteArray = EcdhKeyAgreement::deriveSharedSecret,
        deriveSessionKey: (ByteArray) -> ByteArray = HkdfSessionKeyDerivation::deriveSessionKey
    ): PeerSessionEstablishmentResult {
        require(remotePeerId.isNotBlank()) {
            "Remote peer id must not be blank."
        }

        val remotePeerPublicKey = try {
            Sec1PublicKeyEncoding.decodeUncompressed(remotePeerPublicKeyBytes.copyOf())
        } catch (error: IllegalArgumentException) {
            return PeerSessionEstablishmentResult.InvalidRemotePublicKey(
                reason = error.message ?: "Remote peer public key is invalid."
            )
        }

        val localPublicKeyBytes = localIdentity.publicKeyBytes()
        if (localPublicKeyBytes.contentEquals(remotePeerPublicKeyBytes)) {
            return PeerSessionEstablishmentResult.SelfPeer(
                reason = "Remote peer public key matches the local agreement key."
            )
        }

        val sharedSecret = try {
            deriveSharedSecret(
                localIdentity.privateKey,
                remotePeerPublicKey
            )
        } catch (error: IllegalArgumentException) {
            return PeerSessionEstablishmentResult.KeyAgreementFailed(
                reason = error.message ?: "Peer session key agreement failed."
            )
        } catch (error: RuntimeException) {
            return PeerSessionEstablishmentResult.KeyAgreementFailed(
                reason = error.message ?: "Peer session key agreement failed."
            )
        }

        val sessionKey = try {
            deriveSessionKey(sharedSecret.copyOf())
        } catch (error: IllegalArgumentException) {
            return PeerSessionEstablishmentResult.KeyDerivationFailed(
                reason = error.message ?: "Peer session key derivation failed."
            )
        } catch (error: RuntimeException) {
            return PeerSessionEstablishmentResult.KeyDerivationFailed(
                reason = error.message ?: "Peer session key derivation failed."
            )
        }

        return PeerSessionEstablishmentResult.Established(
            session = EstablishedPeerSession(
                peerId = remotePeerId,
                peerPublicKey = remotePeerPublicKeyBytes.copyOf(),
                outgoingMaterial = OutgoingMessageSendEncryptionMaterial(
                    senderPublicKey = localPublicKeyBytes,
                    keyBytes = sessionKey.copyOf()
                ),
                incomingMaterial = IncomingMessageReceiveDecryptionMaterial(
                    keyBytes = sessionKey.copyOf()
                )
            )
        )
    }

    fun establishAndStore(
        localIdentity: LocalPeerSessionIdentityMaterial,
        remotePeerId: String,
        remotePeerPublicKeyBytes: ByteArray,
        registry: PeerSessionRegistry,
        deriveSharedSecret: (
            PrivateKey,
            ECPublicKey
        ) -> ByteArray = EcdhKeyAgreement::deriveSharedSecret,
        deriveSessionKey: (ByteArray) -> ByteArray = HkdfSessionKeyDerivation::deriveSessionKey
    ): PeerSessionEstablishmentResult {
        val result = establish(
            localIdentity = localIdentity,
            remotePeerId = remotePeerId,
            remotePeerPublicKeyBytes = remotePeerPublicKeyBytes,
            deriveSharedSecret = deriveSharedSecret,
            deriveSessionKey = deriveSessionKey
        )
        if (result is PeerSessionEstablishmentResult.Established) {
            registry.putSession(result.session)
        }
        return result
    }
}
