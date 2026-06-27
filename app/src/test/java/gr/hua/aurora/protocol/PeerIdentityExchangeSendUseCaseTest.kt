package gr.hua.aurora.protocol

import gr.hua.aurora.ble.transport.BleGattTransportFrameReassembler
import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlan
import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class PeerIdentityExchangeSendUseCaseTest {
    @Test
    fun buildsIdentityFramePreservingPeerIdAndPublicKey() {
        val local = generateEcKeyPair()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)

        val result = runSuspending {
            PeerIdentityExchangeSendUseCase.send(
                localPeerId = "peer-local",
                localPublicAgreementKeyBytes = local.publicKeyBytes(),
                privateChatProposalId = null,
                targetPeerId = null,
                transportSender = sender,
                createdAtMillis = 1_716_100_001L
            )
        }

        val decodedFrame = decodedFrameFromSender(sender)
        val decodedMessage = PeerIdentityExchangeMessage.fromMessageFrame(decodedFrame)

        assertEquals(PeerIdentityExchangeSendResult.SubmittedLocally, result)
        assertEquals(MessageFrameType.IDENTITY_EXCHANGE, decodedFrame.type)
        assertEquals("peer-local", decodedFrame.senderId)
        assertNull(decodedFrame.recipientId)
        assertEquals(1_716_100_001L, decodedFrame.createdAtMillis)
        assertEquals("peer-local", decodedMessage.peerId)
        assertArrayEquals(local.publicKeyBytes(), decodedMessage.publicAgreementKeyBytes())
    }

    @Test
    fun submittedSendPlanReassemblesToIdentityExchangeFrame() {
        val local = generateEcKeyPair()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)

        val result = runSuspending {
            PeerIdentityExchangeSendUseCase.send(
                localPeerId = "peer-local-2",
                localPublicAgreementKeyBytes = local.publicKeyBytes(),
                privateChatProposalId = null,
                targetPeerId = "peer-remote",
                transportSender = sender,
                createdAtMillis = 1_716_100_002L
            )
        }

        val decodedFrame = decodedFrameFromSender(sender)
        val decodedMessage = PeerIdentityExchangeMessage.fromMessageFrame(decodedFrame)

        assertEquals(PeerIdentityExchangeSendResult.SubmittedLocally, result)
        assertEquals("peer-remote", requireNotNull(sender.capturedPlan).targetPeerId)
        assertEquals("peer-remote", decodedFrame.recipientId)
        assertEquals("peer-local-2", decodedMessage.peerId)
        assertArrayEquals(local.publicKeyBytes(), decodedMessage.publicAgreementKeyBytes())
    }

    @Test
    fun noPrivateKeyBytesAreIncluded() {
        val local = generateEcKeyPair()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)

        runSuspending {
            PeerIdentityExchangeSendUseCase.send(
                localPeerId = "peer-local-3",
                localPublicAgreementKeyBytes = local.publicKeyBytes(),
                privateChatProposalId = null,
                targetPeerId = "peer-remote-3",
                transportSender = sender,
                createdAtMillis = 1_716_100_003L
            )
        }

        val encodedFrame = reassembledFrameStringFromSender(sender)
        val privateKeyToken = java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(local.privateKeyBytes())

        assertFalse(encodedFrame.contains(privateKeyToken))
    }

    @Test
    fun fakeSenderSuccessReturnsSubmittedLocally() {
        val result = runSuspending {
            PeerIdentityExchangeSendUseCase.send(
                localPeerId = "peer-local-4",
                localPublicAgreementKeyBytes = generateEcKeyPair().publicKeyBytes(),
                privateChatProposalId = null,
                targetPeerId = "peer-remote-4",
                transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally),
                createdAtMillis = 1_716_100_004L
            )
        }

        assertEquals(PeerIdentityExchangeSendResult.SubmittedLocally, result)
    }

    @Test
    fun fakeSenderNotAvailableMapsToSenderUnavailable() {
        val result = runSuspending {
            PeerIdentityExchangeSendUseCase.send(
                localPeerId = "peer-local-5",
                localPublicAgreementKeyBytes = generateEcKeyPair().publicKeyBytes(),
                privateChatProposalId = null,
                targetPeerId = "peer-remote-5",
                transportSender = RecordingTransportSender(BleTransportSendResult.NotAvailable),
                createdAtMillis = 1_716_100_005L
            )
        }

        assertEquals(PeerIdentityExchangeSendResult.SenderUnavailable, result)
    }

    @Test
    fun fakeSenderFailedPropagatesReason() {
        val result = runSuspending {
            PeerIdentityExchangeSendUseCase.send(
                localPeerId = "peer-local-6",
                localPublicAgreementKeyBytes = generateEcKeyPair().publicKeyBytes(),
                privateChatProposalId = null,
                targetPeerId = "peer-remote-6",
                transportSender = RecordingTransportSender(
                    BleTransportSendResult.Failed("manual identity exchange failed")
                ),
                createdAtMillis = 1_716_100_006L
            )
        }

        assertEquals(
            PeerIdentityExchangeSendResult.Failed("manual identity exchange failed"),
            result
        )
    }

    @Test
    fun invalidLocalPeerIdIsRejected() {
        val result = runSuspending {
            PeerIdentityExchangeSendUseCase.send(
                localPeerId = "   ",
                localPublicAgreementKeyBytes = generateEcKeyPair().publicKeyBytes(),
                privateChatProposalId = null,
                targetPeerId = "peer-remote-7",
                transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally),
                createdAtMillis = 1_716_100_007L
            )
        }

        assertTrue(result is PeerIdentityExchangeSendResult.InvalidLocalIdentity)
    }

    @Test
    fun invalidLocalPublicKeyIsRejected() {
        val invalidPublicKey = ByteArray(65).apply {
            this[0] = 0x04
        }

        val result = runSuspending {
            PeerIdentityExchangeSendUseCase.send(
                localPeerId = "peer-local-8",
                localPublicAgreementKeyBytes = invalidPublicKey,
                privateChatProposalId = null,
                targetPeerId = "peer-remote-8",
                transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally),
                createdAtMillis = 1_716_100_008L
            )
        }

        assertTrue(result is PeerIdentityExchangeSendResult.InvalidLocalIdentity)
    }

    @Test
    fun targetPeerIdIsPreservedInSendPlanIfProvided() {
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)

        runSuspending {
            PeerIdentityExchangeSendUseCase.send(
                localPeerId = "peer-local-9",
                localPublicAgreementKeyBytes = generateEcKeyPair().publicKeyBytes(),
                privateChatProposalId = null,
                targetPeerId = "peer-remote-9",
                transportSender = sender,
                createdAtMillis = 1_716_100_009L
            )
        }

        assertEquals("peer-remote-9", requireNotNull(sender.capturedPlan).targetPeerId)
    }

    private fun decodedFrameFromSender(
        sender: RecordingTransportSender
    ): MessageFrame {
        return MessageFrameCodec.decode(reassembledFrameStringFromSender(sender))
    }

    private fun reassembledFrameStringFromSender(
        sender: RecordingTransportSender
    ): String {
        val reassembledBytes = BleGattTransportFrameReassembler.reassemble(
            requireNotNull(sender.capturedPlan).framesInSendOrder()
        )
        return String(reassembledBytes, UTF_8)
    }

    private fun generateEcKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private fun KeyPair.publicKey(): ECPublicKey {
        return public as ECPublicKey
    }

    private fun KeyPair.privateKey(): ECPrivateKey {
        return private as ECPrivateKey
    }

    private fun KeyPair.publicKeyBytes(): ByteArray {
        return Sec1PublicKeyEncoding.encodeUncompressed(publicKey())
    }

    private fun KeyPair.privateKeyBytes(): ByteArray {
        val scalarBytes = privateKey().s.toByteArray()
        return when {
            scalarBytes.size == 32 -> scalarBytes
            scalarBytes.size < 32 -> ByteArray(32 - scalarBytes.size) + scalarBytes
            scalarBytes.size == 33 && scalarBytes[0] == 0.toByte() -> {
                scalarBytes.copyOfRange(1, scalarBytes.size)
            }
            else -> throw IllegalArgumentException(
                "Private key scalar does not fit in 32 bytes: ${scalarBytes.size}."
            )
        }
    }

    private class RecordingTransportSender(
        private val result: BleTransportSendResult
    ) : BleTransportSender {
        var capturedPlan: OutgoingBleTransportSendPlan? = null

        override fun send(
            plan: OutgoingBleTransportSendPlan,
            listener: BleTransportSender.Listener
        ) {
            capturedPlan = plan
            listener.onSendResult(result)
        }
    }

    private fun <T> runSuspending(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            }
        )

        return requireNotNull(outcome) {
            "Suspending use-case did not complete synchronously in the test harness."
        }.getOrThrow()
    }
}
