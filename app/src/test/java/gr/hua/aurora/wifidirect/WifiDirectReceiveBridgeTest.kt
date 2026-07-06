package gr.hua.aurora.wifidirect

import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlanBuilder
import gr.hua.aurora.ble.transport.BleTransportReceiveResult
import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.EncryptedMessageEnvelopeCodec
import gr.hua.aurora.protocol.LocalPeerSessionIdentityMaterial
import gr.hua.aurora.protocol.MessageFrame
import gr.hua.aurora.protocol.MessageFrameCodec
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.OutgoingMessageSendEncryptionMaterial
import gr.hua.aurora.protocol.PeerSessionEstablisher
import gr.hua.aurora.protocol.PeerSessionEstablishmentResult
import gr.hua.aurora.protocol.PeerSessionPeerId
import gr.hua.aurora.protocol.PeerSessionRegistry
import gr.hua.aurora.protocol.PrivateChatTransportFrameFactory
import gr.hua.aurora.state.AuroraStateHolder
import gr.hua.aurora.state.SampleAuroraState
import gr.hua.aurora.state.createAuroraBleTransportFrameReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class WifiDirectReceiveBridgeTest {
    @Test
    fun receiveBridgeDefaultsToDisabledAndSkipsProcessing() {
        var processedCount = 0
        val bridge = WifiDirectReceiveBridge { frame ->
            processedCount += 1
            BleTransportReceiveResult.Buffered(
                groupId = 1,
                receivedChunks = 1,
                expectedChunks = 1
            )
        }

        bridge.onTransportFrameReceived(
            WifiDirectTransportFrame.fromPayload(validBleTransportFrameBytes())
        )

        assertEquals(0, processedCount)
        assertEquals(false, bridge.currentDiagnostics().enabled)
        assertEquals(0L, bridge.currentDiagnostics().framesBridged)
        assertEquals(0L, bridge.currentDiagnostics().bridgeFailures)
    }

    @Test
    fun setEnabledRecordsToggleActionAndResult() {
        val bridge = WifiDirectReceiveBridge {
            BleTransportReceiveResult.Buffered(
                groupId = 1,
                receivedChunks = 1,
                expectedChunks = 1
            )
        }

        bridge.setEnabled(true)

        assertEquals("Enable receive bridge", bridge.currentDiagnostics().lastToggleAction)
        assertEquals("enabled", bridge.currentDiagnostics().lastToggleResult)
        assertNull(bridge.currentDiagnostics().lastToggleBlockedReason)
    }

    @Test
    fun recordBlockedToggleKeepsBridgeDisabledAndCapturesReason() {
        val bridge = WifiDirectReceiveBridge {
            BleTransportReceiveResult.Buffered(
                groupId = 1,
                receivedChunks = 1,
                expectedChunks = 1
            )
        }

        bridge.recordBlockedToggle(
            enabled = true,
            reason = "Cannot enable receive bridge: adapter not ready (Waiting for receive adapter.)."
        )

        assertEquals(false, bridge.currentDiagnostics().enabled)
        assertEquals("Enable receive bridge", bridge.currentDiagnostics().lastToggleAction)
        assertEquals("blocked", bridge.currentDiagnostics().lastToggleResult)
        assertEquals(
            "Cannot enable receive bridge: adapter not ready (Waiting for receive adapter.).",
            bridge.currentDiagnostics().lastToggleBlockedReason
        )
    }

    @Test
    fun receiveBridgeDisabledSkipsSmokeTransportFrames() {
        var processedCount = 0
        val bridge = WifiDirectReceiveBridge { _ ->
            processedCount += 1
            BleTransportReceiveResult.Buffered(
                groupId = 1,
                receivedChunks = 1,
                expectedChunks = 1
            )
        }

        smokeTransportFrames().forEach(bridge::onTransportFrameReceived)

        assertEquals(0, processedCount)
        assertEquals(0L, bridge.currentDiagnostics().framesBridged)
    }

    @Test
    fun enabledReceiveBridgePassesValidAuroraTransportFrameToProcessor() {
        val receivedFrames = mutableListOf<BleGattTransportFrame>()
        val bridge = WifiDirectReceiveBridge { frame ->
            receivedFrames += frame
            BleTransportReceiveResult.Buffered(
                groupId = 7,
                receivedChunks = 1,
                expectedChunks = 2
            )
        }

        bridge.setEnabled(true)
        val payload = validBleTransportFrameBytes()
        bridge.onTransportFrameReceived(
            WifiDirectTransportFrame.fromPayload(payload)
        )

        assertEquals(1, receivedFrames.size)
        assertEquals(1L, bridge.currentDiagnostics().transportFramesObserved)
        assertEquals(0L, bridge.currentDiagnostics().framesBridged)
        assertEquals(0L, bridge.currentDiagnostics().bridgeFailures)
        assertEquals(payload.size, bridge.currentDiagnostics().lastTransportFrameSize)
        assertEquals("buffered", bridge.currentDiagnostics().lastBridgeResult)
        assertNull(bridge.currentDiagnostics().lastBridgeError)
    }

    @Test
    fun enabledReceiveBridgePassesSmokeTransportFramesToProcessor() {
        val receivedFrames = mutableListOf<BleGattTransportFrame>()
        val smokeFrames = smokeTransportFrames()
        val bridge = WifiDirectReceiveBridge { frame ->
            receivedFrames += frame
            BleTransportReceiveResult.Buffered(
                groupId = 7,
                receivedChunks = receivedFrames.size,
                expectedChunks = smokeFrames.size
            )
        }

        bridge.setEnabled(true)
        smokeFrames.forEach(bridge::onTransportFrameReceived)

        assertEquals(smokeFrames.size, receivedFrames.size)
        assertEquals(
            smokeFrames.size.toLong(),
            bridge.currentDiagnostics().transportFramesObserved
        )
        assertEquals(0L, bridge.currentDiagnostics().framesBridged)
        assertEquals(0L, bridge.currentDiagnostics().bridgeFailures)
        assertEquals("buffered", bridge.currentDiagnostics().lastBridgeResult)
        assertTrue(receivedFrames.all { frame -> frame.bodyToByteArray().isNotEmpty() })
    }

    @Test
    fun receiveBridgeDisabledPreventsWifiDirectGlobalFrameFromAffectingGlobalUi() {
        val holder = createHolder()
        val bridge = WifiDirectReceiveBridge(
            createAuroraBleTransportFrameReceiver(holder)::receive
        )
        val publicFrame = globalMessageFrame(
            id = "wifi-direct-disabled-1",
            senderId = "peer-disabled"
        )

        publicTransportFramesFor(publicFrame).forEach { frame ->
            bridge.onTransportFrameReceived(
                WifiDirectTransportFrame.fromPayload(frame.toByteArray())
            )
        }

        assertTrue(holder.uiState.globalMessages.none { it.id == publicFrame.id })
        assertEquals(0L, bridge.currentDiagnostics().framesBridged)
    }

    @Test
    fun enabledReceiveBridgePassesValidGlobalFrameThroughExistingReceivePipeline() {
        val holder = createHolder()
        val bridge = WifiDirectReceiveBridge(
            createAuroraBleTransportFrameReceiver(holder)::receive
        )
        val publicFrame = globalMessageFrame(
            id = "wifi-direct-global-1",
            senderId = "peer-global"
        )
        val transportFrames = publicTransportFramesFor(publicFrame)

        bridge.setEnabled(true)
        transportFrames.forEach { frame ->
            bridge.onTransportFrameReceived(
                WifiDirectTransportFrame.fromPayload(frame.toByteArray())
            )
        }

        val receivedMessage = holder.uiState.globalMessages.single { it.id == publicFrame.id }
        assertEquals(publicFrame.payload, receivedMessage.text)
        assertEquals(publicFrame.senderId, receivedMessage.senderId)
        assertEquals(MessageStatus.RECEIVED, receivedMessage.status)
        assertEquals(transportFrames.size.toLong(), bridge.currentDiagnostics().transportFramesObserved)
        assertEquals(1L, bridge.currentDiagnostics().framesBridged)
        assertEquals(0L, bridge.currentDiagnostics().duplicateFramesDropped)
        assertEquals("processed", bridge.currentDiagnostics().lastBridgeResult)
        assertNull(bridge.currentDiagnostics().lastBridgeError)
    }

    @Test
    fun receiveBridgeDisabledPreventsWifiDirectPrivateFrameFromAffectingPrivateUi() {
        val fixture = createPrivateIncomingFixture(messageId = "wifi-direct-private-disabled")
        val bridge = WifiDirectReceiveBridge(
            createAuroraBleTransportFrameReceiver(
                stateHolder = fixture.holder,
                sessionMaterialProvider = fixture.registry
            )::receive
        )

        fixture.transportFrames.forEach { frame ->
            bridge.onTransportFrameReceived(
                WifiDirectTransportFrame.fromPayload(frame.toByteArray())
            )
        }

        assertTrue(
            fixture.holder.privateMessagesForPeerId(fixture.remotePeerId).isEmpty()
        )
        assertEquals(0L, bridge.currentDiagnostics().framesBridged)
    }

    @Test
    fun enabledReceiveBridgePassesValidPrivateFrameThroughExistingReceivePipeline() {
        val fixture = createPrivateIncomingFixture(messageId = "wifi-direct-private-enabled")
        val bridge = WifiDirectReceiveBridge(
            createAuroraBleTransportFrameReceiver(
                stateHolder = fixture.holder,
                sessionMaterialProvider = fixture.registry
            )::receive
        )

        bridge.setEnabled(true)
        fixture.transportFrames.forEach { frame ->
            bridge.onTransportFrameReceived(
                WifiDirectTransportFrame.fromPayload(frame.toByteArray())
            )
        }

        val receivedMessage = fixture.holder.privateMessagesForPeerId(fixture.remotePeerId)
            .single { it.id == "wifi-direct-private-enabled" }
        assertEquals("hello private", receivedMessage.text)
        assertEquals(fixture.remotePeerId, receivedMessage.senderId)
        assertEquals(MessageStatus.RECEIVED, receivedMessage.status)
        assertEquals(
            fixture.transportFrames.size.toLong(),
            bridge.currentDiagnostics().transportFramesObserved
        )
        assertEquals(1L, bridge.currentDiagnostics().framesBridged)
        assertEquals("processed", bridge.currentDiagnostics().lastBridgeResult)
        assertNull(bridge.currentDiagnostics().lastBridgeError)
    }

    @Test
    fun samePrivateTextWithDifferentMessageIdsIsNotTreatedAsDuplicate() {
        val setup = createPrivateReceiveSetup()
        val firstFixture = createPrivateIncomingFixture(
            messageId = "wifi-direct-private-one",
            setup = setup
        )
        val secondFixture = createPrivateIncomingFixture(
            messageId = "wifi-direct-private-two",
            setup = setup
        )
        val bridge = WifiDirectReceiveBridge(
            createAuroraBleTransportFrameReceiver(
                stateHolder = setup.holder,
                sessionMaterialProvider = setup.registry
            )::receive
        )

        bridge.setEnabled(true)
        firstFixture.transportFrames.forEach { frame ->
            bridge.onTransportFrameReceived(
                WifiDirectTransportFrame.fromPayload(frame.toByteArray())
            )
        }
        secondFixture.transportFrames.forEach { frame ->
            bridge.onTransportFrameReceived(
                WifiDirectTransportFrame.fromPayload(frame.toByteArray())
            )
        }

        val messages = setup.holder.privateMessagesForPeerId(setup.remotePeerId)
        assertEquals(
            listOf("wifi-direct-private-one", "wifi-direct-private-two"),
            messages.map { it.id }
        )
        assertTrue(messages.all { it.text == "hello private" })
        assertEquals(2L, bridge.currentDiagnostics().framesBridged)
        assertEquals(0L, bridge.currentDiagnostics().duplicateFramesDropped)
    }

    @Test
    fun duplicatePrivateMessageReplayCountsOneLogicalDeliveryAndOneDuplicate() {
        val setup = createPrivateReceiveSetup()
        val fixture = createPrivateIncomingFixture(
            messageId = "wifi-direct-private-duplicate",
            setup = setup
        )
        val bridge = WifiDirectReceiveBridge(
            createAuroraBleTransportFrameReceiver(
                stateHolder = setup.holder,
                sessionMaterialProvider = setup.registry
            )::receive
        )

        bridge.setEnabled(true)
        fixture.transportFrames.forEach { frame ->
            bridge.onTransportFrameReceived(
                WifiDirectTransportFrame.fromPayload(frame.toByteArray())
            )
        }
        fixture.transportFrames.forEach { frame ->
            bridge.onTransportFrameReceived(
                WifiDirectTransportFrame.fromPayload(frame.toByteArray())
            )
        }

        assertEquals(
            1,
            setup.holder.privateMessagesForPeerId(setup.remotePeerId)
                .count { it.id == "wifi-direct-private-duplicate" }
        )
        assertEquals(1L, bridge.currentDiagnostics().framesBridged)
        assertEquals(1L, bridge.currentDiagnostics().duplicateFramesDropped)
        assertEquals("duplicate message", bridge.currentDiagnostics().lastBridgeResult)
        assertNull(bridge.currentDiagnostics().lastBridgeError)
    }

    @Test
    fun duplicateGlobalMessageReplayCountsOneLogicalDeliveryAndOneDuplicate() {
        val holder = createHolder()
        val bridge = WifiDirectReceiveBridge(
            createAuroraBleTransportFrameReceiver(holder)::receive
        )
        val publicFrame = globalMessageFrame(
            id = "wifi-direct-global-duplicate",
            senderId = "peer-global"
        )
        val transportFrames = publicTransportFramesFor(publicFrame)

        bridge.setEnabled(true)
        transportFrames.forEach { frame ->
            bridge.onTransportFrameReceived(
                WifiDirectTransportFrame.fromPayload(frame.toByteArray())
            )
        }
        transportFrames.forEach { frame ->
            bridge.onTransportFrameReceived(
                WifiDirectTransportFrame.fromPayload(frame.toByteArray())
            )
        }

        assertEquals(
            1,
            holder.uiState.globalMessages.count { it.id == publicFrame.id }
        )
        assertEquals(
            (transportFrames.size * 2).toLong(),
            bridge.currentDiagnostics().transportFramesObserved
        )
        assertEquals(1L, bridge.currentDiagnostics().framesBridged)
        assertEquals(1L, bridge.currentDiagnostics().duplicateFramesDropped)
        assertEquals("duplicate message", bridge.currentDiagnostics().lastBridgeResult)
        assertNull(bridge.currentDiagnostics().lastBridgeError)
    }

    @Test
    fun globalDebugSendFlowsFromClientToServerAndBackToClient() {
        val link = ConnectedWifiDirectGlobalDebugLink()

        try {
            link.clientGlobalSender.submitGlobalMessage(
                message = sampleOutgoingMessage(
                    messageId = "wifi-client-global-1",
                    userText = "hello from client"
                ),
                senderId = "client-debug-user"
            )
            awaitCondition {
                link.serverHolder.uiState.globalMessages.any { it.id == "wifi-client-global-1" }
            }

            link.serverGlobalSender.submitGlobalMessage(
                message = sampleOutgoingMessage(
                    messageId = "wifi-server-global-1",
                    userText = "hello from server one"
                ),
                senderId = "server-debug-user"
            )
            link.serverGlobalSender.submitGlobalMessage(
                message = sampleOutgoingMessage(
                    messageId = "wifi-server-global-2",
                    userText = "hello from server two"
                ),
                senderId = "server-debug-user"
            )
            awaitCondition {
                link.clientHolder.uiState.globalMessages.count { message ->
                    message.id == "wifi-server-global-1" || message.id == "wifi-server-global-2"
                } == 2
            }

            assertEquals(
                1,
                link.serverHolder.uiState.globalMessages.count { it.id == "wifi-client-global-1" }
            )
            assertEquals(
                2,
                link.clientHolder.uiState.globalMessages.count { message ->
                    message.id == "wifi-server-global-1" || message.id == "wifi-server-global-2"
                }
            )
            assertEquals(1L, link.serverReceiveBridge.currentDiagnostics().framesBridged)
            assertEquals(2L, link.clientReceiveBridge.currentDiagnostics().framesBridged)
            assertEquals(0L, link.serverReceiveBridge.currentDiagnostics().duplicateFramesDropped)
            assertEquals(0L, link.clientReceiveBridge.currentDiagnostics().duplicateFramesDropped)
            assertNull(link.serverReceiveBridge.currentDiagnostics().lastBridgeError)
            assertNull(link.clientReceiveBridge.currentDiagnostics().lastBridgeError)
        } finally {
            link.dispose()
        }
    }

    @Test
    fun enabledReceiveBridgeFailsCleanlyForInvalidAuroraTransportPayload() {
        var processedCount = 0
        val bridge = WifiDirectReceiveBridge { _ ->
            processedCount += 1
            BleTransportReceiveResult.Buffered(
                groupId = 1,
                receivedChunks = 1,
                expectedChunks = 1
            )
        }

        bridge.setEnabled(true)
        bridge.onTransportFrameReceived(
            WifiDirectTransportFrame.fromPayload("hello".toByteArray())
        )

        assertEquals(0, processedCount)
        assertEquals(0L, bridge.currentDiagnostics().framesBridged)
        assertEquals(1L, bridge.currentDiagnostics().bridgeFailures)
        assertEquals("failed", bridge.currentDiagnostics().lastBridgeResult)
        assertEquals(
            "Invalid Aurora transport frame payload.",
            bridge.currentDiagnostics().lastBridgeError
        )
    }

    @Test
    fun enabledReceiveBridgeRecordsProcessorFailures() {
        val bridge = WifiDirectReceiveBridge {
            BleTransportReceiveResult.InvalidChunk(
                reason = "Transport frame does not contain a valid chunk body."
            )
        }

        bridge.setEnabled(true)
        bridge.onTransportFrameReceived(
            WifiDirectTransportFrame.fromPayload(validBleTransportFrameBytes())
        )

        assertEquals(1L, bridge.currentDiagnostics().transportFramesObserved)
        assertEquals(0L, bridge.currentDiagnostics().framesBridged)
        assertEquals(1L, bridge.currentDiagnostics().bridgeFailures)
        assertEquals("failed", bridge.currentDiagnostics().lastBridgeResult)
        assertEquals(
            "Transport frame does not contain a valid chunk body.",
            bridge.currentDiagnostics().lastBridgeError
        )
    }

    @Test
    fun disablingReceiveBridgeClearsEnabledState() {
        val bridge = WifiDirectReceiveBridge {
            BleTransportReceiveResult.Buffered(
                groupId = 1,
                receivedChunks = 1,
                expectedChunks = 1
            )
        }

        bridge.setEnabled(true)
        bridge.disable()

        assertTrue(!bridge.currentDiagnostics().enabled)
    }

    @Test
    fun resetDiagnosticsClearsCountersWithoutDisablingReceiveBridge() {
        val bridge = WifiDirectReceiveBridge {
            BleTransportReceiveResult.Buffered(
                groupId = 1,
                receivedChunks = 1,
                expectedChunks = 1
            )
        }

        bridge.setEnabled(true)
        bridge.onTransportFrameReceived(
            WifiDirectTransportFrame.fromPayload(validBleTransportFrameBytes())
        )
        bridge.resetDiagnostics()

        assertTrue(bridge.currentDiagnostics().enabled)
        assertEquals(0L, bridge.currentDiagnostics().transportFramesObserved)
        assertEquals(0L, bridge.currentDiagnostics().framesBridged)
        assertEquals(0L, bridge.currentDiagnostics().bridgeFailures)
        assertNull(bridge.currentDiagnostics().lastTransportFrameSize)
        assertNull(bridge.currentDiagnostics().lastToggleAction)
        assertNull(bridge.currentDiagnostics().lastToggleResult)
        assertNull(bridge.currentDiagnostics().lastToggleBlockedReason)
        assertNull(bridge.currentDiagnostics().lastBridgeResult)
        assertNull(bridge.currentDiagnostics().lastBridgeError)
    }

    private fun validBleTransportFrameBytes(): ByteArray {
        return requireNotNull(
            BleGattTransportFrame.create(
                body = byteArrayOf(0x01, 0x02, 0x03)
            )
        ).toByteArray()
    }

    private fun smokeTransportFrames(): List<WifiDirectTransportFrame> {
        val submittedFrames = mutableListOf<WifiDirectTransportFrame>()
        val sender = WifiDirectSmokeTestSender(
            submitFrame = { frame, onResult ->
                submittedFrames += frame
                onResult(Result.success(Unit))
            },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = true) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.READY
                )
            },
            nowMillis = { 1_717_000_002L }
        )

        sender.sendPublicSmokeTest("debug-user")

        return submittedFrames.toList()
    }

    private fun publicTransportFramesFor(
        frame: MessageFrame
    ): List<BleGattTransportFrame> {
        return OutgoingBleTransportSendPlanBuilder.build(
            messageId = frame.id,
            targetPeerId = null,
            encryptedEnvelopeBytes = MessageFrameCodec.encode(frame).toByteArray(UTF_8),
            sourceCreatedAtMillis = frame.createdAtMillis
        ).framesInSendOrder()
    }

    private fun globalMessageFrame(
        id: String,
        senderId: String
    ): MessageFrame {
        return MessageFrame(
            id = id,
            type = MessageFrameType.GLOBAL_TEXT,
            senderId = senderId,
            createdAtMillis = 1_717_000_010L,
            payload = "hello over wifi direct"
        )
    }

    private fun createPrivateReceiveSetup(): PrivateReceiveSetup {
        val holder = createHolder()
        val local = generateEcKeyPair()
        val remote = generateEcKeyPair()
        val localIdentity = local.identity()
        val remoteIdentity = remote.identity()
        val localPeerId = PeerSessionPeerId.deriveFromPublicKey(local.publicKeyBytes())
        val remotePeerId = PeerSessionPeerId.deriveFromPublicKey(remote.publicKeyBytes())
        val registry = PeerSessionRegistry()
        val localEstablishment = PeerSessionEstablisher.establishAndStore(
            localIdentity = localIdentity,
            remotePeerId = remotePeerId,
            remotePeerPublicKeyBytes = remote.publicKeyBytes(),
            registry = registry
        )
        assertTrue(localEstablishment is PeerSessionEstablishmentResult.Established)
        val remoteEstablishment = PeerSessionEstablisher.establish(
            localIdentity = remoteIdentity,
            remotePeerId = localPeerId,
            remotePeerPublicKeyBytes = local.publicKeyBytes()
        )
        assertTrue(remoteEstablishment is PeerSessionEstablishmentResult.Established)
        val remoteOutgoingMaterial =
            (remoteEstablishment as PeerSessionEstablishmentResult.Established).session.outgoingMaterial

        holder.addOrUpdateContact(
            canonicalPeerId = remotePeerId,
            displayName = "Alex",
            hasSession = true
        )
        holder.recordReceivedPrivateChatProposal(
            peerId = remotePeerId,
            remoteProposalId = "remote-proposal-1"
        )
        val privateChatId = requireNotNull(
            holder.privateChatIdentityForPeerId(remotePeerId)?.privateChatId
        )

        return PrivateReceiveSetup(
            holder = holder,
            registry = registry,
            localPeerId = localPeerId,
            remotePeerId = remotePeerId,
            remoteOutgoingMaterial = remoteOutgoingMaterial,
            privateChatId = privateChatId
        )
    }

    private fun createPrivateIncomingFixture(
        messageId: String,
        userText: String = "hello private",
        setup: PrivateReceiveSetup = createPrivateReceiveSetup()
    ): PrivateIncomingFixture {
        val preparedFrame = PrivateChatTransportFrameFactory.build(
            message = OutgoingChatMessage(
                messageId = messageId,
                threadId = "private:${setup.localPeerId}",
                userText = userText,
                createdAtMillis = 1_717_000_020L,
                status = MessageStatus.QUEUED
            ),
            privateChatId = setup.privateChatId,
            senderPeerId = setup.remotePeerId,
            senderUsername = "Remote Alex",
            encryptionMaterial = setup.remoteOutgoingMaterial
        )
        val transportFrames = OutgoingBleTransportSendPlanBuilder.build(
            messageId = preparedFrame.frame.id,
            targetPeerId = preparedFrame.targetPeerId,
            encryptedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(
                preparedFrame.encryptedEnvelope
            ).toByteArray(UTF_8),
            sourceCreatedAtMillis = preparedFrame.frame.createdAtMillis
        ).framesInSendOrder()

        return PrivateIncomingFixture(
            holder = setup.holder,
            registry = setup.registry,
            remotePeerId = setup.remotePeerId,
            transportFrames = transportFrames
        )
    }

    private fun createHolder(): AuroraStateHolder {
        return AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
    }

    private class FakeProfileStore : LocalProfileSettingsStore {
        override fun loadProfileSettings(): LocalProfileSettings {
            return LocalProfileSettings(
                generatedUsername = "PIAIUFN1",
                customUsername = null,
                useCustomUsernameInGlobalChat = true
            )
        }

        override fun saveGeneratedUsername(username: String) = Unit

        override fun saveCustomUsername(username: String?) = Unit

        override fun saveUseCustomUsernameInGlobalChat(enabled: Boolean) = Unit

        override fun clearProfile() = Unit
    }

    private data class PrivateIncomingFixture(
        val holder: AuroraStateHolder,
        val registry: PeerSessionRegistry,
        val remotePeerId: String,
        val transportFrames: List<BleGattTransportFrame>
    )

    private data class PrivateReceiveSetup(
        val holder: AuroraStateHolder,
        val registry: PeerSessionRegistry,
        val localPeerId: String,
        val remotePeerId: String,
        val remoteOutgoingMaterial: OutgoingMessageSendEncryptionMaterial,
        val privateChatId: String
    )

    private fun sampleOutgoingMessage(
        messageId: String,
        userText: String
    ): OutgoingChatMessage {
        return OutgoingChatMessage(
            messageId = messageId,
            threadId = "global",
            userText = userText,
            createdAtMillis = 1_717_000_030L,
            status = MessageStatus.LOCAL_ONLY
        )
    }

    private inner class ConnectedWifiDirectGlobalDebugLink {
        val serverHolder = createHolder()
        val clientHolder = createHolder()
        val serverController = AndroidWifiDirectSocketController(requestedPort = 0)
        val clientController: AndroidWifiDirectSocketController
        val serverTransportAdapter: WifiDirectTransportAdapter
        val clientTransportAdapter: WifiDirectTransportAdapter
        val serverReceiveBridge: WifiDirectReceiveBridge
        val clientReceiveBridge: WifiDirectReceiveBridge
        val serverSendBridge: WifiDirectSendBridge
        val clientSendBridge: WifiDirectSendBridge
        val serverGlobalSender: WifiDirectGlobalDebugSendBridge
        val clientGlobalSender: WifiDirectGlobalDebugSendBridge

        private val serverReceiveListener = object : WifiDirectTransportAdapter.Listener {
            override fun onTransportFrameReceived(frame: WifiDirectTransportFrame) {
                serverReceiveBridge.onTransportFrameReceived(frame)
            }
        }
        private val clientReceiveListener = object : WifiDirectTransportAdapter.Listener {
            override fun onTransportFrameReceived(frame: WifiDirectTransportFrame) {
                clientReceiveBridge.onTransportFrameReceived(frame)
            }
        }

        init {
            serverController.startServer(hostHint = "192.168.49.1")
            awaitCondition {
                serverController.currentDiagnostics().state == WifiDirectSocketState.SERVER_LISTENING
            }
            val listeningPort = requireNotNull(serverController.currentDiagnostics().endpoint?.port)
            clientController = AndroidWifiDirectSocketController(requestedPort = listeningPort)
            clientController.connectClient("127.0.0.1")
            awaitCondition {
                clientController.currentDiagnostics().state == WifiDirectSocketState.CONNECTED &&
                    serverController.currentDiagnostics().state == WifiDirectSocketState.CONNECTED
            }

            serverTransportAdapter = WifiDirectTransportAdapter(
                frameSink = serverController,
                frameSource = serverController,
                enabled = true
            )
            clientTransportAdapter = WifiDirectTransportAdapter(
                frameSink = clientController,
                frameSource = clientController,
                enabled = true
            )
            serverReceiveBridge = WifiDirectReceiveBridge(
                createAuroraBleTransportFrameReceiver(serverHolder)::receive
            )
            clientReceiveBridge = WifiDirectReceiveBridge(
                createAuroraBleTransportFrameReceiver(clientHolder)::receive
            )
            serverTransportAdapter.addListener(serverReceiveListener)
            clientTransportAdapter.addListener(clientReceiveListener)
            serverReceiveBridge.setEnabled(true)
            clientReceiveBridge.setEnabled(true)

            serverSendBridge = WifiDirectSendBridge(serverTransportAdapter).apply {
                setEnabled(true)
            }
            clientSendBridge = WifiDirectSendBridge(clientTransportAdapter).apply {
                setEnabled(true)
            }
            serverGlobalSender = WifiDirectGlobalDebugSendBridge(
                submitFrame = serverSendBridge::submit,
                sendBridgeDiagnostics = serverSendBridge::currentDiagnostics,
                transportAdapterDiagnostics = serverTransportAdapter::currentDiagnostics
            ).apply {
                setEnabled(true)
            }
            clientGlobalSender = WifiDirectGlobalDebugSendBridge(
                submitFrame = clientSendBridge::submit,
                sendBridgeDiagnostics = clientSendBridge::currentDiagnostics,
                transportAdapterDiagnostics = clientTransportAdapter::currentDiagnostics
            ).apply {
                setEnabled(true)
            }
        }

        fun dispose() {
            serverGlobalSender.disable()
            clientGlobalSender.disable()
            serverSendBridge.disable()
            clientSendBridge.disable()
            serverTransportAdapter.removeListener(serverReceiveListener)
            clientTransportAdapter.removeListener(clientReceiveListener)
            serverTransportAdapter.dispose()
            clientTransportAdapter.dispose()
            clientController.dispose()
            serverController.dispose()
        }
    }

    private fun awaitCondition(
        timeoutMillis: Long = 5_000L,
        condition: () -> Boolean
    ) {
        val startMillis = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - startMillis > timeoutMillis) {
                throw AssertionError("Timed out waiting for condition.")
            }
            Thread.sleep(25L)
        }
    }

    private fun generateEcKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private fun KeyPair.identity(): LocalPeerSessionIdentityMaterial {
        return LocalPeerSessionIdentityMaterial(
            publicKeyBytes = publicKeyBytes(),
            privateKey = privateKey()
        )
    }

    private fun KeyPair.privateKey(): ECPrivateKey {
        return private as ECPrivateKey
    }

    private fun KeyPair.publicKey(): ECPublicKey {
        return public as ECPublicKey
    }

    private fun KeyPair.publicKeyBytes(): ByteArray {
        return gr.hua.aurora.crypto.Sec1PublicKeyEncoding.encodeUncompressed(publicKey())
    }
}
