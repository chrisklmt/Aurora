package gr.hua.aurora.wifidirect

import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.EncryptedMessageEnvelopeCodec
import gr.hua.aurora.protocol.MessageFrame
import gr.hua.aurora.protocol.MessageFrameCodec
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.OutgoingMessageSendEncryptionMaterial
import gr.hua.aurora.protocol.PreparedPrivateChatTransportFrame
import gr.hua.aurora.protocol.PrivateChatMessagePayloadCodec
import gr.hua.aurora.protocol.PrivateChatTransportFrameFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class WifiDirectPrivateDebugSendBridgeTest {
    @Test
    fun privateDebugSendDefaultsToDisabled() {
        val bridge = WifiDirectPrivateDebugSendBridge(
            submitFrame = { _, _ -> error("submit should not be called") },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics() },
            transportAdapterDiagnostics = { WifiDirectTransportAdapterDiagnostics() }
        )

        assertFalse(bridge.currentDiagnostics().enabled)
        assertTrue(bridge.currentDiagnostics().bleRemainsPrimary)
        assertEquals(0L, bridge.currentDiagnostics().privateSubmissionAttempts)
        assertEquals(0L, bridge.currentDiagnostics().privateSubmissionSuccesses)
        assertEquals(0L, bridge.currentDiagnostics().privateSubmitFailures)
    }

    @Test
    fun privateDebugSendIsBlockedWhenSendBridgeDisabled() {
        val submittedFrames = mutableListOf<WifiDirectTransportFrame>()
        val bridge = WifiDirectPrivateDebugSendBridge(
            submitFrame = { frame, onResult ->
                submittedFrames += frame
                onResult(Result.success(Unit))
            },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = false) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.READY
                )
            }
        )
        bridge.setEnabled(true)

        val failure = runCatching {
            bridge.submitPrivateMessage(samplePreparedFrame()) { result ->
                result.getOrThrow()
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            "Wi-Fi Direct Private send requires the send bridge to be enabled.",
            failure?.message
        )
        assertEquals(emptyList<WifiDirectTransportFrame>(), submittedFrames)
        assertEquals(1L, bridge.currentDiagnostics().privateSubmissionAttempts)
        assertEquals(0L, bridge.currentDiagnostics().privateSubmissionSuccesses)
        assertEquals(1L, bridge.currentDiagnostics().privateSubmitFailures)
        assertEquals("private-msg-1", bridge.currentDiagnostics().lastPrivateMessageId)
        assertEquals("blocked", bridge.currentDiagnostics().lastPrivateSendResult)
    }

    @Test
    fun privateDebugSendIsBlockedWhenAdapterIsNotReady() {
        val submittedFrames = mutableListOf<WifiDirectTransportFrame>()
        val bridge = WifiDirectPrivateDebugSendBridge(
            submitFrame = { frame, onResult ->
                submittedFrames += frame
                onResult(Result.success(Unit))
            },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = true) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.NOT_READY
                )
            }
        )
        bridge.setEnabled(true)

        val failure = runCatching {
            bridge.submitPrivateMessage(samplePreparedFrame()) { result ->
                result.getOrThrow()
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            "Wi-Fi Direct Private send requires a ready transport adapter.",
            failure?.message
        )
        assertEquals(emptyList<WifiDirectTransportFrame>(), submittedFrames)
        assertEquals(1L, bridge.currentDiagnostics().privateSubmissionAttempts)
        assertEquals(0L, bridge.currentDiagnostics().privateSubmissionSuccesses)
        assertEquals(1L, bridge.currentDiagnostics().privateSubmitFailures)
        assertEquals("private-msg-1", bridge.currentDiagnostics().lastPrivateMessageId)
        assertEquals("blocked", bridge.currentDiagnostics().lastPrivateSendResult)
    }

    @Test
    fun privateDebugSendBuildsValidPrivateTransportFrames() {
        val submittedFrames = mutableListOf<WifiDirectTransportFrame>()
        val bridge = WifiDirectPrivateDebugSendBridge(
            submitFrame = { frame, onResult ->
                submittedFrames += frame
                onResult(Result.success(Unit))
            },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = true) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.READY
                )
            }
        )
        val preparedFrame = samplePreparedFrame()
        bridge.setEnabled(true)

        bridge.submitPrivateMessage(preparedFrame)

        assertTrue(submittedFrames.isNotEmpty())
        val decodedFrame = decodeRecordedFrame(
            submittedFrames = submittedFrames,
            material = testEncryptionMaterial()
        )
        val decodedPayload = PrivateChatMessagePayloadCodec.decode(decodedFrame.payload)

        assertEquals(preparedFrame.frame.id, decodedFrame.id)
        assertEquals(MessageFrameType.PRIVATE_TEXT, decodedFrame.type)
        assertEquals(preparedFrame.targetPeerId, decodedFrame.recipientId)
        assertEquals("Alice", decodedPayload.senderUsername)
        assertEquals("hello private", decodedPayload.body)
        assertEquals("chat-alex", decodedPayload.privateChatId)
        assertEquals(1L, bridge.currentDiagnostics().privateSubmissionAttempts)
        assertEquals(1L, bridge.currentDiagnostics().privateSubmissionSuccesses)
        assertEquals(0L, bridge.currentDiagnostics().privateSubmitFailures)
        assertEquals("private-msg-1", bridge.currentDiagnostics().lastPrivateMessageId)
        assertEquals("alex", bridge.currentDiagnostics().lastPrivateTargetPeerId)
        assertEquals("submitted locally", bridge.currentDiagnostics().lastPrivateSendResult)
    }

    @Test
    fun privateDebugSendRecordsTransportFailure() {
        val bridge = WifiDirectPrivateDebugSendBridge(
            submitFrame = { _, onResult ->
                onResult(Result.failure(IllegalStateException("socket write failed")))
            },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = true) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.READY
                )
            }
        )
        bridge.setEnabled(true)

        val failure = runCatching {
            bridge.submitPrivateMessage(samplePreparedFrame()) { result ->
                result.getOrThrow()
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("socket write failed", failure?.message)
        assertEquals(1L, bridge.currentDiagnostics().privateSubmissionAttempts)
        assertEquals(0L, bridge.currentDiagnostics().privateSubmissionSuccesses)
        assertEquals(1L, bridge.currentDiagnostics().privateSubmitFailures)
        assertEquals("failed", bridge.currentDiagnostics().lastPrivateSendResult)
        assertEquals("socket write failed", bridge.currentDiagnostics().lastPrivateSendError)
    }

    @Test
    fun privateAndGlobalDebugTogglesRemainIndependent() {
        val globalBridge = WifiDirectGlobalDebugSendBridge(
            submitFrame = { _, onResult -> onResult(Result.success(Unit)) },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = true) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(state = WifiDirectTransportAdapterState.READY)
            }
        )
        val privateBridge = WifiDirectPrivateDebugSendBridge(
            submitFrame = { _, onResult -> onResult(Result.success(Unit)) },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = true) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(state = WifiDirectTransportAdapterState.READY)
            }
        )

        globalBridge.setEnabled(true)

        assertTrue(globalBridge.currentDiagnostics().enabled)
        assertFalse(privateBridge.currentDiagnostics().enabled)

        privateBridge.setEnabled(true)

        assertTrue(globalBridge.currentDiagnostics().enabled)
        assertTrue(privateBridge.currentDiagnostics().enabled)
    }

    @Test
    fun resetDiagnosticsClearsResultsWithoutDisablingPrivateDebugSend() {
        val bridge = WifiDirectPrivateDebugSendBridge(
            submitFrame = { _, onResult -> onResult(Result.success(Unit)) },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = true) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.READY
                )
            }
        )
        bridge.setEnabled(true)
        bridge.submitPrivateMessage(samplePreparedFrame())

        bridge.resetDiagnostics()

        assertTrue(bridge.currentDiagnostics().enabled)
        assertEquals(0L, bridge.currentDiagnostics().privateSubmissionAttempts)
        assertEquals(0L, bridge.currentDiagnostics().privateSubmissionSuccesses)
        assertEquals(0L, bridge.currentDiagnostics().privateSubmitFailures)
        assertEquals(null, bridge.currentDiagnostics().lastPrivateMessageId)
        assertEquals(null, bridge.currentDiagnostics().lastPrivateTargetPeerId)
        assertEquals(null, bridge.currentDiagnostics().lastPrivateFrameSize)
        assertEquals(null, bridge.currentDiagnostics().lastPrivateSendResult)
        assertEquals(null, bridge.currentDiagnostics().lastPrivateSendError)
    }

    private fun samplePreparedFrame(): PreparedPrivateChatTransportFrame {
        return PrivateChatTransportFrameFactory.build(
            message = OutgoingChatMessage(
                messageId = "private-msg-1",
                threadId = "private:alex",
                userText = "hello private",
                createdAtMillis = 1_717_100_001L,
                status = MessageStatus.QUEUED
            ),
            privateChatId = "chat-alex",
            senderPeerId = "sender-canonical",
            senderUsername = "Alice",
            encryptionMaterial = testEncryptionMaterial()
        )
    }

    private fun testEncryptionMaterial(): OutgoingMessageSendEncryptionMaterial {
        return OutgoingMessageSendEncryptionMaterial(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = ByteArray(32) { index -> (index + 41).toByte() },
            authenticatedData = "private-wifi-direct-aad".toByteArray(UTF_8)
        )
    }

    private fun senderPublicKeyBytes(): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val publicKey = generator.generateKeyPair().public as ECPublicKey
        return gr.hua.aurora.crypto.Sec1PublicKeyEncoding.encodeUncompressed(publicKey)
    }

    private fun decodeRecordedFrame(
        submittedFrames: List<WifiDirectTransportFrame>,
        material: OutgoingMessageSendEncryptionMaterial
    ): MessageFrame {
        val transportFrames = submittedFrames.map { submittedFrame ->
            requireNotNull(BleGattTransportFrame.parse(submittedFrame.payloadBytes()))
        }
        val envelopeBytes = gr.hua.aurora.ble.transport.BleGattTransportFrameReassembler.reassemble(
            transportFrames
        )
        val envelope = EncryptedMessageEnvelopeCodec.decode(String(envelopeBytes, UTF_8))
        val frameBytes = gr.hua.aurora.protocol.EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = envelope,
            keyBytes = material.keyBytes,
            authenticatedData = material.authenticatedData
        )
        return MessageFrameCodec.decode(String(frameBytes, UTF_8))
    }
}
