package gr.hua.aurora.protocol

sealed interface PeerIdentityExchangeHandlingResult {
    data class Established(
        val peerId: String
    ) : PeerIdentityExchangeHandlingResult {
        init {
            require(peerId.isNotBlank()) {
                "Peer identity exchange established peerId must not be blank."
            }
        }
    }

    data object IgnoredNonIdentityFrame : PeerIdentityExchangeHandlingResult

    data class InvalidIdentityMessage(
        val reason: String
    ) : PeerIdentityExchangeHandlingResult {
        init {
            require(reason.isNotBlank()) {
                "Peer identity exchange invalid message reason must not be blank."
            }
        }
    }

    data class InvalidRemotePublicKey(
        val reason: String
    ) : PeerIdentityExchangeHandlingResult {
        init {
            require(reason.isNotBlank()) {
                "Peer identity exchange invalid remote public key reason must not be blank."
            }
        }
    }

    data class SelfPeer(
        val reason: String
    ) : PeerIdentityExchangeHandlingResult {
        init {
            require(reason.isNotBlank()) {
                "Peer identity exchange self peer reason must not be blank."
            }
        }
    }

    data class KeyAgreementFailed(
        val reason: String
    ) : PeerIdentityExchangeHandlingResult {
        init {
            require(reason.isNotBlank()) {
                "Peer identity exchange key agreement failure reason must not be blank."
            }
        }
    }

    data class KeyDerivationFailed(
        val reason: String
    ) : PeerIdentityExchangeHandlingResult {
        init {
            require(reason.isNotBlank()) {
                "Peer identity exchange key derivation failure reason must not be blank."
            }
        }
    }
}

object PeerIdentityExchangeHandler {
    fun handle(
        frame: MessageFrame,
        localIdentity: LocalPeerSessionIdentityMaterial,
        registry: PeerSessionRegistry,
        establishAndStore: (
            LocalPeerSessionIdentityMaterial,
            String,
            ByteArray,
            PeerSessionRegistry
        ) -> PeerSessionEstablishmentResult = PeerSessionEstablisher::establishAndStore
    ): PeerIdentityExchangeHandlingResult {
        if (frame.type != MessageFrameType.IDENTITY_EXCHANGE) {
            return PeerIdentityExchangeHandlingResult.IgnoredNonIdentityFrame
        }

        val message = try {
            PeerIdentityExchangeMessage.fromMessageFrame(frame)
        } catch (error: IllegalArgumentException) {
            return mapInvalidIdentityFailure(error)
        }
        val claimedPeerId = PeerSessionPeerId.sanitize(message.peerId)
        val canonicalPeerId = try {
            PeerSessionPeerId.deriveFromPublicKey(message.publicAgreementKeyBytes())
        } catch (error: IllegalArgumentException) {
            return PeerIdentityExchangeHandlingResult.InvalidRemotePublicKey(
                reason = error.message ?: "Remote peer public key is invalid."
            )
        }

        return when (
            val result = establishAndStore(
                localIdentity,
                canonicalPeerId,
                message.publicAgreementKeyBytes(),
                registry
            )
        ) {
            is PeerSessionEstablishmentResult.Established -> {
                if (claimedPeerId != canonicalPeerId) {
                    registry.putPeerIdAlias(
                        aliasPeerId = claimedPeerId,
                        canonicalPeerId = canonicalPeerId
                    )
                }
                PeerIdentityExchangeHandlingResult.Established(
                    peerId = result.session.peerId
                )
            }
            is PeerSessionEstablishmentResult.InvalidRemotePublicKey -> {
                PeerIdentityExchangeHandlingResult.InvalidRemotePublicKey(
                    reason = result.reason
                )
            }
            is PeerSessionEstablishmentResult.SelfPeer -> {
                PeerIdentityExchangeHandlingResult.SelfPeer(
                    reason = result.reason
                )
            }
            is PeerSessionEstablishmentResult.KeyAgreementFailed -> {
                PeerIdentityExchangeHandlingResult.KeyAgreementFailed(
                    reason = result.reason
                )
            }
            is PeerSessionEstablishmentResult.KeyDerivationFailed -> {
                PeerIdentityExchangeHandlingResult.KeyDerivationFailed(
                    reason = result.reason
                )
            }
        }
    }

    private fun mapInvalidIdentityFailure(
        error: IllegalArgumentException
    ): PeerIdentityExchangeHandlingResult {
        val reason = error.message ?: "Peer identity exchange message is invalid."
        return if (reason.contains("SEC1", ignoreCase = true) ||
            reason.contains("public key", ignoreCase = true)
        ) {
            PeerIdentityExchangeHandlingResult.InvalidRemotePublicKey(
                reason = reason
            )
        } else {
            PeerIdentityExchangeHandlingResult.InvalidIdentityMessage(
                reason = reason
            )
        }
    }
}
