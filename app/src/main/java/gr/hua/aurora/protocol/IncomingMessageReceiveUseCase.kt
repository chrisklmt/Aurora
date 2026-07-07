package gr.hua.aurora.protocol

import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleGattTransportFrameReassembler
import java.nio.charset.StandardCharsets.UTF_8
import java.security.GeneralSecurityException

class IncomingMessageReceiveDecryptionMaterial(
    keyBytes: ByteArray,
    authenticatedData: ByteArray? = null
) {
    private val storedKeyBytes = keyBytes.copyOf()
    private val storedAuthenticatedData = authenticatedData?.copyOf()

    val keyBytes: ByteArray
        get() = storedKeyBytes.copyOf()

    val authenticatedData: ByteArray?
        get() = storedAuthenticatedData?.copyOf()
}

object IncomingMessageReceiveUseCase {
    fun receive(
        frames: Collection<BleGattTransportFrame>,
        sessionMaterialProvider: IncomingSessionMaterialProvider
    ): IncomingTransportReceiveResult {
        val frameList = frames.toList()
        val encodedEnvelopeBytes = try {
            BleGattTransportFrameReassembler.reassemble(frameList)
        } catch (error: IllegalArgumentException) {
            return IncomingTransportReceiveResult.IncompleteChunks(
                reason = error.message ?: "Incoming transport chunks are incomplete."
            )
        }

        decodePublicMessageFrame(encodedEnvelopeBytes)?.let { publicReceiveResult ->
            return publicReceiveResult
        }

        val envelope = try {
            EncryptedMessageEnvelopeCodec.decode(String(encodedEnvelopeBytes, UTF_8))
        } catch (error: IllegalArgumentException) {
            return IncomingTransportReceiveResult.InvalidEnvelope(
                reason = error.message ?: "Incoming encrypted envelope is invalid."
            )
        }

        val decryptionMaterial = when (
            val lookupResult = sessionMaterialProvider.decryptionMaterialFor(envelope)
        ) {
            is IncomingSessionMaterialLookupResult.Found -> {
                lookupResult.material
            }
            is IncomingSessionMaterialLookupResult.MaterialUnavailable -> {
                relayOnlyEncryptedResultOrNull(envelope)?.let { relayOnlyResult ->
                    return relayOnlyResult
                }
                return IncomingTransportReceiveResult.SessionMaterialUnavailable(
                    reason = lookupResult.reason
                )
            }
            is IncomingSessionMaterialLookupResult.UnsupportedSender -> {
                return IncomingTransportReceiveResult.UnsupportedSender(
                    reason = lookupResult.reason
                )
            }
            is IncomingSessionMaterialLookupResult.InvalidIdentity -> {
                return IncomingTransportReceiveResult.InvalidSenderIdentity(
                    reason = lookupResult.reason
                )
            }
        }

        val decodedFrameBytes = try {
            EncryptedMessageEnvelopeDecryptor.decrypt(
                envelope = envelope,
                keyBytes = decryptionMaterial.keyBytes,
                authenticatedData = decryptionMaterial.authenticatedData
            )
        } catch (error: GeneralSecurityException) {
            return IncomingTransportReceiveResult.DecryptFailed(
                reason = error.message ?: "Incoming encrypted envelope could not be decrypted."
            )
        } catch (error: IllegalArgumentException) {
            return IncomingTransportReceiveResult.DecryptFailed(
                reason = error.message ?: "Incoming encrypted envelope could not be decrypted."
            )
        }

        val frame = try {
            MessageFrameCodec.decode(String(decodedFrameBytes, UTF_8))
        } catch (error: IllegalArgumentException) {
            return IncomingTransportReceiveResult.InvalidFrame(
                reason = error.message ?: "Incoming message frame is invalid."
            )
        }

        return IncomingTransportReceiveResult.Received(
            message = IncomingTransportMessage(
                frame = frame,
                senderPublicKey = envelope.senderPublicKey,
                relayEnvelope = envelope
            )
        )
    }

    private fun relayOnlyEncryptedResultOrNull(
        envelope: EncryptedMessageEnvelope
    ): IncomingTransportReceiveResult.RelayOnlyEncrypted? {
        val relayMetadata = envelope.relayMetadata ?: return null
        if (relayMetadata.messageType != MessageFrameType.PRIVATE_TEXT) {
            return null
        }
        return IncomingTransportReceiveResult.RelayOnlyEncrypted(
            envelope = envelope
        )
    }

    private fun decodePublicMessageFrame(
        encodedBytes: ByteArray
    ): IncomingTransportReceiveResult? {
        val encodedFrame = String(encodedBytes, UTF_8)
        val frame = try {
            MessageFrameCodec.decode(encodedFrame)
        } catch (_: IllegalArgumentException) {
            return null
        }

        return when (frame.type) {
            MessageFrameType.GLOBAL_TEXT -> {
                IncomingTransportReceiveResult.Received(
                    message = IncomingTransportMessage(
                        frame = frame
                    )
                )
            }
            MessageFrameType.IDENTITY_EXCHANGE -> {
                val identityMessage = try {
                    PeerIdentityExchangeMessage.fromMessageFrame(frame)
                } catch (error: IllegalArgumentException) {
                    return IncomingTransportReceiveResult.InvalidFrame(
                        reason = error.message ?: "Incoming identity exchange frame is invalid."
                    )
                }

                IncomingTransportReceiveResult.Received(
                    message = IncomingTransportMessage(
                        frame = frame,
                        senderPublicKey = identityMessage.publicAgreementKeyBytes()
                    )
                )
            }
            MessageFrameType.PRIVATE_TEXT,
            MessageFrameType.HYBRID_TRANSPORT_CONTROL,
            MessageFrameType.CONTROL -> null
        }
    }
}
