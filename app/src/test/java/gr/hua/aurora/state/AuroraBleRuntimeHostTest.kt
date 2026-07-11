package gr.hua.aurora.state

import android.Manifest
import gr.hua.aurora.ble.connection.BleConnectionStatus
import gr.hua.aurora.ble.noop.NoOpBleTransportSender
import gr.hua.aurora.ble.discovery.BleDiscoveredDevice
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import gr.hua.aurora.ble.transport.BleGattTransportFrameChunker
import gr.hua.aurora.ble.transport.AndroidBleTransportSender
import gr.hua.aurora.ble.transport.BleGattTransportChunk
import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleGattTransportFrameWriteResult
import gr.hua.aurora.ble.transport.BleGattTransportFrameWriter
import gr.hua.aurora.ble.transport.BleTransportReceiveResult
import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlan
import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.identity.AndroidKeystoreLocalAgreementKey.PrivateKeyLoadResult
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.EncryptedMessageEnvelopeBuilder
import gr.hua.aurora.protocol.EncryptedMessageEnvelopeCodec
import gr.hua.aurora.protocol.EncryptedMessageRelayMetadata
import gr.hua.aurora.protocol.IncomingTransportMessage
import gr.hua.aurora.protocol.IncomingTransportReceiveResult
import gr.hua.aurora.protocol.LocalPeerSessionIdentityMaterial
import gr.hua.aurora.protocol.MessageFrame
import gr.hua.aurora.protocol.MessageFrameCodec
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.GlobalMeshDeliveryCoordinator
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.OutgoingSessionMaterialLookupResult
import gr.hua.aurora.protocol.OutgoingSessionMaterialProvider
import gr.hua.aurora.protocol.OutgoingMessageSendEncryptionMaterial
import gr.hua.aurora.protocol.PeerIdentityExchangeMessage
import gr.hua.aurora.protocol.PeerIdentityExchangeHandlingResult
import gr.hua.aurora.protocol.PeerIdentityExchangeSendResult
import gr.hua.aurora.protocol.PrivateChatMessagePayload
import gr.hua.aurora.protocol.PrivateChatMessagePayloadCodec
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
import gr.hua.aurora.protocol.PeerSessionEstablisher
import gr.hua.aurora.protocol.SeenMessageIdCache
import gr.hua.aurora.transport.processing.IncomingTransportFrameProcessingResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapAttemptDecision
import gr.hua.aurora.transport.hybrid.HybridBootstrapAttemptCommandBuildResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapAttemptCommand
import gr.hua.aurora.transport.hybrid.HybridBootstrapAttemptRequest
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandExecutorConfig
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandExecutorFactory
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandExecutorMode
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandTriggerResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapCandidateSelection
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandTriggerController
import gr.hua.aurora.transport.hybrid.HybridBootstrapDiagnostics
import gr.hua.aurora.transport.hybrid.HybridBootstrapDecisionProvider
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandExecutionResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandExecutor
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualTriggerSnapshot
import gr.hua.aurora.transport.hybrid.HybridBootstrapSocketEndpoint
import gr.hua.aurora.transport.hybrid.HybridBootstrapSocketEndpointResolution
import gr.hua.aurora.transport.hybrid.HybridTransportControlFrameFactory
import gr.hua.aurora.transport.hybrid.HybridTransportControlMessage
import gr.hua.aurora.transport.hybrid.InMemoryHybridTransportControlStore
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportFrame
import gr.hua.aurora.wifidirect.transport.WifiDirectTransportSendResult
import gr.hua.aurora.wifidirect.transport.WifiDirectTransportSender
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class AuroraBleRuntimeHostTest {
    @Test
    fun runtimeStartsWhenAvailabilityAndReadinessAreOnline() {
        assertTrue(
            shouldRunAuroraBleRuntime(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                bluetoothStatus = readyBluetoothStatus(),
                isAppVisible = true
            )
        )
    }

    @Test
    fun runtimeStaysStoppedWhenLocationIsDisabled() {
        assertFalse(
            shouldRunAuroraBleRuntime(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                bluetoothStatus = readyBluetoothStatus(
                    isLocationEnabled = false
                ),
                isAppVisible = true
            )
        )
    }

    @Test
    fun runtimeStaysStoppedWhenBluetoothIsDisabled() {
        assertFalse(
            shouldRunAuroraBleRuntime(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                bluetoothStatus = readyBluetoothStatus(
                    isBluetoothEnabled = false
                ),
                isAppVisible = true
            )
        )
    }

    @Test
    fun runtimeStaysStoppedWhenUserPreferenceIsOffline() {
        assertFalse(
            shouldRunAuroraBleRuntime(
                desiredAvailability = AuroraAvailabilityPreference.OFFLINE,
                bluetoothStatus = readyBluetoothStatus(),
                isAppVisible = true
            )
        )
    }

    @Test
    fun runtimeStaysStoppedWhenAppIsNotVisible() {
        assertFalse(
            shouldRunAuroraBleRuntime(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                bluetoothStatus = readyBluetoothStatus(),
                isAppVisible = false
            )
        )
    }

    @Test
    fun runtimeExposesNoOpSenderWhenFrameWriterIsUnavailable() {
        val sender = createAuroraBleTransportSender(transportFrameWriter = null)

        assertTrue(sender is NoOpBleTransportSender)
        assertEquals("NoOp", auroraTransportSenderSourceLabel(sender))
    }

    @Test
    fun runtimeExposesAndroidSenderWhenFrameWriterIsAvailable() {
        val sender = createAuroraBleTransportSender(
            transportFrameWriter = object : BleGattTransportFrameWriter {
                override fun write(
                    frame: BleGattTransportFrame,
                    listener: BleGattTransportFrameWriter.Listener
                ) {
                    listener.onWriteResult(BleGattTransportFrameWriteResult.Accepted)
                }
            }
        )

        assertTrue(sender is AndroidBleTransportSender)
        assertEquals("Android connector-backed", auroraTransportSenderSourceLabel(sender))
    }

    @Test
    fun runtimeStartDecisionStaysIndependentFromTransportSenderAvailability() {
        val shouldRunWithoutWriter = shouldRunAuroraBleRuntime(
            desiredAvailability = AuroraAvailabilityPreference.ONLINE,
            bluetoothStatus = readyBluetoothStatus(),
            isAppVisible = true
        )
        createAuroraBleTransportSender(transportFrameWriter = null)
        val shouldRunWithWriter = shouldRunAuroraBleRuntime(
            desiredAvailability = AuroraAvailabilityPreference.ONLINE,
            bluetoothStatus = readyBluetoothStatus(),
            isAppVisible = true
        )

        assertTrue(shouldRunWithoutWriter)
        assertTrue(shouldRunWithWriter)
    }

    @Test
    fun runtimeDiscoveredPeerIdsAreDerivedWithoutNearbyScreenOwnership() {
        val firstPeer = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:01",
            stableIdHex = "1032547611223344"
        )
        val secondPeer = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:02",
            stableIdHex = "2032547611223344"
        )

        val peerIds = discoveredAuroraPeerIds(listOf(secondPeer, firstPeer))
        val selectedPeer = choosePublicMeshConnectOnSendPeer(
            reachablePeers = listOf(secondPeer, firstPeer)
        )

        assertEquals(
            listOf("1032547611223344", "2032547611223344"),
            peerIds
        )
        assertEquals("1032547611223344", runtimeReachablePeerId(requireNotNull(selectedPeer)))
    }

    @Test
    fun globalChatWithReachablePeerButNoActiveTransportAttemptsConnectOnSend() {
        val coordinator = GlobalMeshDeliveryCoordinator()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val reachablePeer = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:03",
            stableIdHex = "3032547611223344"
        )
        val statusUpdates = mutableListOf<String>()

        val result = runSuspending {
            submitPublicGlobalMeshMessage(
                message = globalOutgoingMessage("connect-on-send-success"),
                senderId = "sender-1",
                coordinator = coordinator,
                transportSender = transportSender,
                activeTransportPeerId = null,
                isActiveTransportConnected = false,
                reachablePeers = listOf(reachablePeer),
                connectToReachablePeer = {
                    PublicMeshConnectOnSendResult.Connected(
                        peerId = runtimeReachablePeerId(it)
                    )
                },
                onConnectOnSendStatusChanged = statusUpdates::add
            )
        }

        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("3032547611223344"),
            result
        )
        assertEquals(1, transportSender.sendCallCount)
        assertEquals(
            listOf(
                "Mesh connect-on-send: pending for 3032547611223344.",
                "Mesh connect-on-send: succeeded for 3032547611223344."
            ),
            statusUpdates
        )
    }

    @Test
    fun globalChatConnectOnSendFailureReturnsExplicitResult() {
        val coordinator = GlobalMeshDeliveryCoordinator()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val reachablePeer = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:04",
            stableIdHex = "4032547611223344"
        )

        val result = runSuspending {
            submitPublicGlobalMeshMessage(
                message = globalOutgoingMessage("connect-on-send-failure"),
                senderId = "sender-2",
                coordinator = coordinator,
                transportSender = transportSender,
                activeTransportPeerId = null,
                isActiveTransportConnected = false,
                reachablePeers = listOf(reachablePeer),
                connectToReachablePeer = {
                    PublicMeshConnectOnSendResult.Failed(
                        peerId = runtimeReachablePeerId(it),
                        reason = "connection did not reach ready state"
                    )
                }
            )
        }

        assertEquals(
            GlobalMeshDeliveryResult.ConnectOnSendFailed(
                peerId = "4032547611223344",
                reason = "connection did not reach ready state"
            ),
            result
        )
        assertEquals(0, transportSender.sendCallCount)
    }

    @Test
    fun globalChatWithoutReachablePeersReturnsNoReachablePeers() {
        val coordinator = GlobalMeshDeliveryCoordinator()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)

        val result = runSuspending {
            submitPublicGlobalMeshMessage(
                message = globalOutgoingMessage("connect-on-send-none"),
                senderId = "sender-3",
                coordinator = coordinator,
                transportSender = transportSender,
                activeTransportPeerId = null,
                isActiveTransportConnected = false,
                reachablePeers = emptyList(),
                connectToReachablePeer = {
                    error("connect-on-send should not run without reachable peers")
                }
            )
        }

        assertEquals(GlobalMeshDeliveryResult.NoReachablePeers, result)
        assertEquals(0, transportSender.sendCallCount)
    }

    @Test
    fun globalChatWithoutWifiDirectSenderKeepsBleOnlyBehavior() {
        val coordinator = GlobalMeshDeliveryCoordinator()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val reachablePeer = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:05",
            stableIdHex = "5032547611223344"
        )

        val result = runSuspending {
            submitPublicGlobalMeshMessage(
                message = globalOutgoingMessage("wifi-direct-null"),
                senderId = "sender-4",
                coordinator = coordinator,
                transportSender = transportSender,
                wifiDirectTransportSender = null,
                activeTransportPeerId = "5032547611223344",
                activeTransportDeviceAddress = reachablePeer.address,
                isActiveTransportConnected = true,
                reachablePeers = listOf(reachablePeer),
                connectToReachablePeer = {
                    error("connect-on-send should not run with an active transport peer")
                }
            )
        }

        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("5032547611223344"),
            result
        )
        assertEquals(1, transportSender.sendCallCount)
    }

    @Test
    fun globalChatWithWifiDirectNotReadyKeepsBleBehavior() {
        val coordinator = GlobalMeshDeliveryCoordinator()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val wifiDirectSender = RecordingWifiDirectTransportSender(
            WifiDirectTransportSendResult.NotReady("Socket transport not ready.")
        )
        val reachablePeer = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:06",
            stableIdHex = "6032547611223344"
        )

        val result = runSuspending {
            submitPublicGlobalMeshMessage(
                message = globalOutgoingMessage("wifi-direct-not-ready"),
                senderId = "sender-5",
                coordinator = coordinator,
                transportSender = transportSender,
                wifiDirectTransportSender = wifiDirectSender,
                activeTransportPeerId = "6032547611223344",
                activeTransportDeviceAddress = reachablePeer.address,
                isActiveTransportConnected = true,
                reachablePeers = listOf(reachablePeer),
                connectToReachablePeer = {
                    error("connect-on-send should not run with an active transport peer")
                }
            )
        }

        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("6032547611223344"),
            result
        )
        assertEquals(1, transportSender.sendCallCount)
        assertEquals(1, wifiDirectSender.sendCallCount)
    }

    @Test
    fun globalChatWithWifiDirectSuccessSendsExpectedFrameCopy() {
        val coordinator = GlobalMeshDeliveryCoordinator()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val wifiDirectSender = RecordingWifiDirectTransportSender(
            WifiDirectTransportSendResult.Success
        )
        val reachablePeer = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:07",
            stableIdHex = "7032547611223344"
        )
        val message = globalOutgoingMessage("wifi-direct-success")

        val result = runSuspending {
            submitPublicGlobalMeshMessage(
                message = message,
                senderId = "sender-6",
                coordinator = coordinator,
                transportSender = transportSender,
                wifiDirectTransportSender = wifiDirectSender,
                activeTransportPeerId = "7032547611223344",
                activeTransportDeviceAddress = reachablePeer.address,
                isActiveTransportConnected = true,
                reachablePeers = listOf(reachablePeer),
                connectToReachablePeer = {
                    error("connect-on-send should not run with an active transport peer")
                }
            )
        }

        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("7032547611223344"),
            result
        )
        assertEquals(1, transportSender.sendCallCount)
        assertEquals(1, wifiDirectSender.sendCallCount)
        val copiedFrame = decodeWifiDirectMessageFrame(
            wifiDirectSender.sentFrames.single()
        )
        assertEquals(message.messageId, copiedFrame.id)
        assertEquals(MessageFrameType.GLOBAL_TEXT, copiedFrame.type)
        assertEquals("sender-6", copiedFrame.senderId)
        assertEquals(message.userText, copiedFrame.payload)
        assertEquals(message.createdAtMillis, copiedFrame.createdAtMillis)
        assertEquals(10, copiedFrame.ttl)
    }

    @Test
    fun globalChatWithWifiDirectFailureDoesNotOverrideBleResult() {
        val coordinator = GlobalMeshDeliveryCoordinator()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val wifiDirectSender = RecordingWifiDirectTransportSender(
            WifiDirectTransportSendResult.Failed("socket writer unavailable")
        )
        val reachablePeer = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:08",
            stableIdHex = "8032547611223344"
        )

        val result = runSuspending {
            submitPublicGlobalMeshMessage(
                message = globalOutgoingMessage("wifi-direct-failed"),
                senderId = "sender-7",
                coordinator = coordinator,
                transportSender = transportSender,
                wifiDirectTransportSender = wifiDirectSender,
                activeTransportPeerId = "8032547611223344",
                activeTransportDeviceAddress = reachablePeer.address,
                isActiveTransportConnected = true,
                reachablePeers = listOf(reachablePeer),
                connectToReachablePeer = {
                    error("connect-on-send should not run with an active transport peer")
                }
            )
        }

        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("8032547611223344"),
            result
        )
        assertEquals(1, transportSender.sendCallCount)
        assertEquals(1, wifiDirectSender.sendCallCount)
    }

    @Test
    fun publicRelayExcludesImmediatePreviousHopAndForwardsOtherEligibleNeighbors() {
        val coordinator = GlobalMeshDeliveryCoordinator()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val sourcePeer = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:0D",
            stableIdHex = "d032547611223344"
        )
        val otherPeer = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:0E",
            stableIdHex = "e032547611223344"
        )
        val connectedPeerIds = mutableListOf<String>()
        val message = IncomingTransportMessage(
            frame = MessageFrame(
                id = "relay-global-1",
                type = MessageFrameType.GLOBAL_TEXT,
                senderId = "peer-origin",
                createdAtMillis = 1_716_300_050L,
                ttl = 5,
                payload = "relay once"
            )
        )

        val result = runSuspending {
            relayReceivedPublicMeshMessage(
                message = message,
                ingestionResult = IncomingMessageIngestionResult.Appended(
                    message = ChatMessage(
                        id = message.frame.id,
                        threadId = "global",
                        senderId = message.frame.senderId,
                        senderName = message.frame.senderId,
                        text = message.frame.payload,
                        createdAtMillis = message.frame.createdAtMillis,
                        status = MessageStatus.RECEIVED,
                        isOutgoing = false
                    )
                ),
                coordinator = coordinator,
                transportSender = transportSender,
                activeTransportPeerId = runtimeReachablePeerId(sourcePeer),
                activeTransportDeviceAddress = sourcePeer.address,
                isActiveTransportConnected = true,
                localPeerId = "local-self",
                reachablePeers = listOf(sourcePeer, otherPeer),
                immediateSourcePeerId = runtimeReachablePeerId(sourcePeer),
                immediateSourceDeviceAddress = sourcePeer.address,
                connectToReachablePeer = {
                    connectedPeerIds += runtimeReachablePeerId(it)
                    PublicMeshConnectOnSendResult.Connected(peerId = runtimeReachablePeerId(it))
                }
            )
        }

        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("e032547611223344"),
            result
        )
        assertEquals(listOf("e032547611223344"), connectedPeerIds)
        assertEquals(listOf("e032547611223344"), transportSender.sentTargetPeerIds)
    }

    @Test
    fun privateChatSendsImmediatelyWhenActivePeerMatchesSelectedContact() {
        val targetPeerId = "5032547611223344"
        val material = testPrivateEncryptionMaterial()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)

        val result = runSuspending {
            submitPrivateEncryptedMessage(
                message = privateMessage(targetPeerId),
                privateChatId = "chat-$targetPeerId",
                senderPeerId = "sender-private",
                senderUsername = "Alice",
                transportSender = transportSender,
                sessionMaterialProvider = FakeOutgoingSessionMaterialProvider(
                    materialByPeerId = mapOf(targetPeerId to material)
                ),
                activeTransportPeerId = targetPeerId,
                isActiveTransportConnected = true,
                reachablePeers = emptyList(),
                connectToReachablePeer = {
                    error("private connect-on-send should not run when the active peer already matches")
                }
            )
        }

        assertEquals(PrivateChatMessageSendResult.SubmittedLocally, result)
        assertEquals(1, transportSender.sendCallCount)
        val decodedFrame = decodePrivateFrame(
            plan = requireNotNull(transportSender.capturedPlan),
            material = material
        )
        val decodedPayload = gr.hua.aurora.protocol.PrivateChatMessagePayloadCodec.decode(decodedFrame.payload)
        assertEquals(MessageFrameType.PRIVATE_TEXT, decodedFrame.type)
        assertEquals(targetPeerId, decodedFrame.recipientId)
        assertEquals("Alice", decodedPayload.senderUsername)
        assertEquals("hello $targetPeerId", decodedPayload.body)
        assertEquals("chat-$targetPeerId", decodedPayload.privateChatId)
    }

    @Test
    fun privateChatAttemptsExactConnectOnSendWhenTargetIsReachableButInactive() {
        val targetPeerId = "6032547611223344"
        val material = testPrivateEncryptionMaterial()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val reachableTarget = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:05",
            stableIdHex = targetPeerId
        )
        var connectedPeerId: String? = null

        val result = runSuspending {
            submitPrivateEncryptedMessage(
                message = privateMessage(targetPeerId),
                privateChatId = "chat-$targetPeerId",
                senderPeerId = "sender-private",
                senderUsername = "Alice",
                transportSender = transportSender,
                sessionMaterialProvider = FakeOutgoingSessionMaterialProvider(
                    materialByPeerId = mapOf(targetPeerId to material)
                ),
                activeTransportPeerId = null,
                isActiveTransportConnected = false,
                reachablePeers = listOf(reachableTarget),
                connectToReachablePeer = {
                    connectedPeerId = runtimeReachablePeerId(it)
                    PublicMeshConnectOnSendResult.Connected(peerId = connectedPeerId!!)
                }
            )
        }

        assertEquals(targetPeerId, connectedPeerId)
        assertEquals(PrivateChatMessageSendResult.SubmittedLocally, result)
        assertEquals(1, transportSender.sendCallCount)
    }

    @Test
    fun privateChatMarksFailedWhenReachableTargetConnectFails() {
        val targetPeerId = "7032547611223344"
        val material = testPrivateEncryptionMaterial()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val reachableTarget = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:06",
            stableIdHex = targetPeerId
        )

        val result = runSuspending {
            submitPrivateEncryptedMessage(
                message = privateMessage(targetPeerId),
                privateChatId = "chat-$targetPeerId",
                senderPeerId = "sender-private",
                senderUsername = "Alice",
                transportSender = transportSender,
                sessionMaterialProvider = FakeOutgoingSessionMaterialProvider(
                    materialByPeerId = mapOf(targetPeerId to material)
                ),
                activeTransportPeerId = null,
                isActiveTransportConnected = false,
                reachablePeers = listOf(reachableTarget),
                connectToReachablePeer = {
                    PublicMeshConnectOnSendResult.Failed(
                        peerId = runtimeReachablePeerId(it),
                        reason = "connection did not reach ready state"
                    )
                }
            )
        }

        assertEquals(
            PrivateChatMessageSendResult.Failed("connection did not reach ready state"),
            result
        )
        assertEquals(0, transportSender.sendCallCount)
    }

    @Test
    fun privateChatCanRelayThroughReachableNeighborEvenWhenTargetIsNotDirectlyReachable() {
        val targetPeerId = "8032547611223344"
        val material = testPrivateEncryptionMaterial()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val relayPeer = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:07",
            stableIdHex = "9032547611223344"
        )

        val result = runSuspending {
            submitPrivateEncryptedMessage(
                message = privateMessage(targetPeerId),
                privateChatId = "chat-$targetPeerId",
                senderPeerId = "sender-private",
                senderUsername = "Alice",
                transportSender = transportSender,
                sessionMaterialProvider = FakeOutgoingSessionMaterialProvider(
                    materialByPeerId = mapOf(targetPeerId to material)
                ),
                activeTransportPeerId = relayPeer.stablePeerId?.toByteArray()?.joinToString("") { byte ->
                    "%02x".format(byte.toInt() and 0xFF)
                },
                isActiveTransportConnected = true,
                reachablePeers = listOf(relayPeer),
                connectToReachablePeer = {
                    error("private mesh relay should use the already-connected relay peer")
                }
            )
        }

        assertEquals(PrivateChatMessageSendResult.SubmittedLocally, result)
        assertEquals(1, transportSender.sendCallCount)
        assertEquals(listOf("9032547611223344"), transportSender.sentTargetPeerIds)
    }

    @Test
    fun privateChatFansOutAcrossReachableMeshNeighbors() {
        val targetPeerId = "a032547611223344"
        val material = testPrivateEncryptionMaterial()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val wrongActivePeerId = "b032547611223344"
        val reachableTarget = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:08",
            stableIdHex = targetPeerId
        )
        val reachableWrongPeer = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:09",
            stableIdHex = wrongActivePeerId
        )
        val connectedPeerIds = mutableListOf<String>()

        val result = runSuspending {
            submitPrivateEncryptedMessage(
                message = privateMessage(targetPeerId),
                privateChatId = "chat-$targetPeerId",
                senderPeerId = "sender-private",
                senderUsername = "Alice",
                transportSender = transportSender,
                sessionMaterialProvider = FakeOutgoingSessionMaterialProvider(
                    materialByPeerId = mapOf(targetPeerId to material)
                ),
                activeTransportPeerId = wrongActivePeerId,
                isActiveTransportConnected = true,
                reachablePeers = listOf(reachableWrongPeer, reachableTarget),
                connectToReachablePeer = {
                    connectedPeerIds += runtimeReachablePeerId(it)
                    PublicMeshConnectOnSendResult.Connected(peerId = runtimeReachablePeerId(it))
                }
            )
        }

        assertEquals(listOf(targetPeerId), connectedPeerIds)
        assertEquals(PrivateChatMessageSendResult.SubmittedLocally, result)
        assertEquals(2, transportSender.sendCallCount)
        assertEquals(
            listOf(wrongActivePeerId, targetPeerId),
            transportSender.sentTargetPeerIds
        )
        assertEquals(targetPeerId, requireNotNull(transportSender.capturedPlan).targetPeerId)
    }

    @Test
    fun privateRelayDuplicateMessageIdIsForwardedOnlyOnce() {
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val relayPeer = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:0F",
            stableIdHex = "f032547611223344"
        )
        val messageId = "relay-private-duplicate"
        val material = testPrivateEncryptionMaterial()
        val privateFrame = MessageFrame(
            id = messageId,
            type = MessageFrameType.PRIVATE_TEXT,
            senderId = "peer-origin",
            recipientId = "peer-target",
            createdAtMillis = 1_716_300_200L,
            payload = PrivateChatMessagePayloadCodec.encode(
                PrivateChatMessagePayload(
                    privateChatId = "chat-peer-target",
                    senderUsername = "Alice",
                    body = "secret"
                )
            )
        )
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = material.senderPublicKey,
            keyBytes = material.keyBytes,
            plaintext = MessageFrameCodec.encode(privateFrame).toByteArray(UTF_8),
            authenticatedData = material.authenticatedData,
            relayMetadata = EncryptedMessageRelayMetadata(
                messageId = messageId,
                messageType = MessageFrameType.PRIVATE_TEXT,
                ttl = 4
            )
        )
        val seenMessageIds = SeenMessageIdCache()

        val firstResult = runSuspending {
            relayPrivateEncryptedMessage(
                envelope = envelope,
                seenMessageIds = seenMessageIds,
                transportSender = transportSender,
                activeTransportPeerId = null,
                activeTransportDeviceAddress = null,
                isActiveTransportConnected = false,
                localPeerId = "local-self",
                reachablePeers = listOf(relayPeer),
                immediateSourcePeerId = "peer-source",
                immediateSourceDeviceAddress = "AA:BB:CC:00:00:10",
                connectToReachablePeer = {
                    PublicMeshConnectOnSendResult.Connected(peerId = runtimeReachablePeerId(it))
                }
            )
        }
        val secondResult = runSuspending {
            relayPrivateEncryptedMessage(
                envelope = envelope,
                seenMessageIds = seenMessageIds,
                transportSender = transportSender,
                activeTransportPeerId = null,
                activeTransportDeviceAddress = null,
                isActiveTransportConnected = false,
                localPeerId = "local-self",
                reachablePeers = listOf(relayPeer),
                immediateSourcePeerId = "peer-source",
                immediateSourceDeviceAddress = "AA:BB:CC:00:00:10",
                connectToReachablePeer = {
                    PublicMeshConnectOnSendResult.Connected(peerId = runtimeReachablePeerId(it))
                }
            )
        }

        assertNotNull(firstResult)
        assertEquals(listOf("f032547611223344"), transportSender.sentTargetPeerIds)
        assertEquals(1, transportSender.sendCallCount)
        assertNull(secondResult)
    }

    @Test
    fun contactSetupConnectsAndSubmitsIdentityExchangeThroughRealTransportPath() {
        val device = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:0A",
            stableIdHex = "c032547611223344"
        )
        val publicKeyBytes = senderPublicKeyBytes()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)

        val result = runSuspending {
            connectAndExchangeIdentityWithPeer(
                device = device,
                transportSender = sender,
                bleConnectionStatus = BleConnectionStatus.IDLE,
                activeTransportPeerId = null,
                activeTransportDeviceAddress = null,
                connectToReachablePeer = {
                    PublicMeshConnectOnSendResult.Connected(peerId = runtimeReachablePeerId(it))
                },
                localIdentityMaterial = RuntimePeerIdentityExchangePublicMaterial(
                    peerId = "local-peer",
                    publicAgreementKeyBytes = publicKeyBytes
                ),
                privateChatProposalId = null
            )
        }

        assertEquals(PeerIdentityExchangeSendResult.SubmittedLocally, result)
        assertEquals(1, sender.sendCallCount)
        val decodedFrame = decodePlaintextFrame(requireNotNull(sender.capturedPlan))
        assertEquals(MessageFrameType.IDENTITY_EXCHANGE, decodedFrame.type)
        assertEquals("local-peer", decodedFrame.senderId)
        assertEquals("c032547611223344", decodedFrame.recipientId)
    }

    @Test
    fun contactSetupFailureKeepsIdentityExchangeExplicitlyUnavailable() {
        val device = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:0B",
            stableIdHex = "d032547611223344"
        )
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)

        val result = runSuspending {
            connectAndExchangeIdentityWithPeer(
                device = device,
                transportSender = sender,
                bleConnectionStatus = BleConnectionStatus.IDLE,
                activeTransportPeerId = null,
                activeTransportDeviceAddress = null,
                connectToReachablePeer = {
                    PublicMeshConnectOnSendResult.Failed(
                        peerId = runtimeReachablePeerId(it),
                        reason = "connection did not reach ready state"
                    )
                },
                localIdentityMaterial = RuntimePeerIdentityExchangePublicMaterial(
                    peerId = "local-peer",
                    publicAgreementKeyBytes = senderPublicKeyBytes()
                ),
                privateChatProposalId = null
            )
        }

        assertEquals(
            PeerIdentityExchangeSendResult.Failed("connection did not reach ready state"),
            result
        )
        assertEquals(0, sender.sendCallCount)
    }

    @Test
    fun resetAuroraLocalIdentityClearsSessionsAndDerivesFreshPeerIdentity() {
        val oldPublicKeyBytes = generateEcKeyPair().publicKeyBytes()
        val newPublicKeyBytes = generateEcKeyPair().publicKeyBytes()
        var clearedSessions = false

        val summary = resetAuroraLocalIdentity(
            identity = gr.hua.aurora.identity.LocalKeyIdentity.default(),
            clearSessionRegistry = {
                clearedSessions = true
            },
            loadExistingPublicKeyBytes = { oldPublicKeyBytes.copyOf() },
            clearLocalIdentityEntries = {
                gr.hua.aurora.identity.AndroidKeystoreLocalAgreementKey.LocalIdentityClearResult(
                    clearedAliases = setOf(
                        gr.hua.aurora.identity.LocalKeyIdentity.DEFAULT_SIGNING_ALIAS,
                        gr.hua.aurora.identity.LocalKeyIdentity.DEFAULT_KEY_AGREEMENT_ALIAS
                    )
                )
            },
            ensureFreshPublicKeyBytes = { newPublicKeyBytes.copyOf() }
        )

        assertTrue(clearedSessions)
        assertNotEquals(summary.previousPeerId, summary.refreshedPeerId)
        assertNotEquals(summary.previousStablePeerId, summary.refreshedStablePeerId)
        assertEquals(
            setOf(
                gr.hua.aurora.identity.LocalKeyIdentity.DEFAULT_SIGNING_ALIAS,
                gr.hua.aurora.identity.LocalKeyIdentity.DEFAULT_KEY_AGREEMENT_ALIAS
            ),
            summary.clearedAliases
        )
    }

    @Test
    fun runtimeFrameReceiverFailsCleanlyWhenSessionMaterialIsUnavailable() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder
        )
        val senderPublicKey = senderPublicKeyBytes()
        val encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(
            EncryptedMessageEnvelopeBuilder.build(
                senderPublicKey = senderPublicKey,
                keyBytes = deterministicKey(101),
                plaintext = MessageFrameCodec.encode(
                    MessageFrame(
                        id = "runtime-no-session",
                        type = MessageFrameType.GLOBAL_TEXT,
                        senderId = "peer-runtime",
                        createdAtMillis = 1_715_700_001L,
                        payload = "hello runtime"
                    )
                ).toByteArray(UTF_8)
            )
        ).toByteArray(UTF_8)
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = encodedEnvelopeBytes,
            groupId = 0x4301
        )
        val result = frames.fold<BleGattTransportFrame, BleTransportReceiveResult?>(null) { _, frame ->
            receiver.receive(frame)
        } ?: error("Expected at least one transport frame.")

        assertTrue(result is BleTransportReceiveResult.ProcessorFailed)
        val failedResult = result as BleTransportReceiveResult.ProcessorFailed
        assertTrue(
            failedResult.processingResult.receiveResult
                is IncomingTransportReceiveResult.SessionMaterialUnavailable
        )
        assertEquals(0, receiver.activeGroupCount())
    }

    @Test
    fun localPeerSessionIdentityMaterialLoadsWhenPublicAndPrivateKeysAreAvailable() {
        val keyPair = generateEcKeyPair()

        val material = loadLocalPeerSessionIdentityMaterialOrNull(
            ensureAgreementKey = {},
            loadPublicKeyBytes = { keyPair.publicKeyBytes() },
            ensurePrivateKey = {
                PrivateKeyLoadResult.Ready(
                    privateKey = keyPair.privateKey(),
                    wasGenerated = false
                )
            }
        )

        assertNotNull(material)
        assertArrayEquals(keyPair.publicKeyBytes(), requireNotNull(material).publicKeyBytes())
    }

    @Test
    fun localPeerSessionIdentityMaterialReturnsNullWhenPrivateKeyIsUnavailable() {
        val keyPair = generateEcKeyPair()

        val material = loadLocalPeerSessionIdentityMaterialOrNull(
            ensureAgreementKey = {},
            loadPublicKeyBytes = { keyPair.publicKeyBytes() },
            ensurePrivateKey = {
                PrivateKeyLoadResult.LoadFailed("Agreement private key unavailable.")
            }
        )

        assertNull(material)
    }

    @Test
    fun localPeerSessionIdentityMaterialReportsPublicKeyUnavailable() {
        val result = loadLocalPeerSessionIdentityMaterialResult(
            ensureAgreementKey = {},
            loadPublicKeyBytes = { null },
            ensurePrivateKey = {
                PrivateKeyLoadResult.Ready(
                    privateKey = generateEcKeyPair().privateKey(),
                    wasGenerated = false
                )
            }
        )

        assertEquals(
            LocalPeerSessionIdentityMaterialLoadResult.PublicKeyUnavailable(),
            result
        )
    }

    @Test
    fun localPeerSessionIdentityMaterialReportsPrivateKeyUnavailable() {
        val keyPair = generateEcKeyPair()

        val result = loadLocalPeerSessionIdentityMaterialResult(
            ensureAgreementKey = {},
            loadPublicKeyBytes = { keyPair.publicKeyBytes() },
            ensurePrivateKey = {
                PrivateKeyLoadResult.LoadFailed("Stored agreement private key unavailable.")
            }
        )

        assertEquals(
            LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyUnavailable(
                reason = "Local agreement private key unavailable: Stored agreement private key unavailable."
            ),
            result
        )
    }

    @Test
    fun localPeerSessionIdentityMaterialReportsRegeneratedPrivateKeyStatus() {
        val keyPair = generateEcKeyPair()

        val result = loadLocalPeerSessionIdentityMaterialResult(
            ensureAgreementKey = {},
            loadPublicKeyBytes = { keyPair.publicKeyBytes() },
            ensurePrivateKey = {
                PrivateKeyLoadResult.RegeneratedAfterInvalidExistingKey(
                    privateKey = keyPair.privateKey()
                )
            }
        )

        assertTrue(result is LocalPeerSessionIdentityMaterialLoadResult.Ready)
        val ready = result as LocalPeerSessionIdentityMaterialLoadResult.Ready
        assertEquals(
            LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyStatus.REGENERATED_INVALID_EXISTING_KEY,
            ready.privateKeyStatus
        )
    }

    @Test
    fun localPeerSessionIdentityMaterialMapsInvalidExistingKeyToSafeUnavailableStatus() {
        val keyPair = generateEcKeyPair()

        val result = loadLocalPeerSessionIdentityMaterialResult(
            ensureAgreementKey = {},
            loadPublicKeyBytes = { keyPair.publicKeyBytes() },
            ensurePrivateKey = {
                PrivateKeyLoadResult.InvalidExistingKey(
                    reason = "Stored agreement private key is incompatible."
                )
            }
        )

        assertEquals(
            LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyUnavailable(
                reason = "Local agreement private key unavailable: Stored agreement private key is incompatible."
            ),
            result
        )
    }

    @Test
    fun identityHandlerStatusReportsReadyAndSpecificKeyFailures() {
        val readyStatus = auroraIdentityHandlerStatusText(
            loadResult = LocalPeerSessionIdentityMaterialLoadResult.Ready(
                material = generateEcKeyPair().identity(),
                privateKeyStatus = LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyStatus.LOADED
            ),
            isHandlerReady = true
        )
        val generatedStatus = auroraIdentityHandlerStatusText(
            loadResult = LocalPeerSessionIdentityMaterialLoadResult.Ready(
                material = generateEcKeyPair().identity(),
                privateKeyStatus = LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyStatus.GENERATED
            ),
            isHandlerReady = true
        )
        val regeneratedStatus = auroraIdentityHandlerStatusText(
            loadResult = LocalPeerSessionIdentityMaterialLoadResult.Ready(
                material = generateEcKeyPair().identity(),
                privateKeyStatus = LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyStatus.REGENERATED_INVALID_EXISTING_KEY
            ),
            isHandlerReady = true
        )
        val privateUnavailableStatus = auroraIdentityHandlerStatusText(
            loadResult = LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyUnavailable(
                reason = "Local agreement private key unavailable: Android Keystore unavailable (KeyStoreException)"
            ),
            isHandlerReady = false
        )
        val publicUnavailableStatus = auroraIdentityHandlerStatusText(
            loadResult = LocalPeerSessionIdentityMaterialLoadResult.PublicKeyUnavailable(),
            isHandlerReady = false
        )

        assertEquals("Identity handler ready. Local agreement private key loaded.", readyStatus)
        assertEquals("Identity handler ready. Local agreement private key generated.", generatedStatus)
        assertEquals(
            "Identity handler ready. Local agreement key was invalid and regenerated.",
            regeneratedStatus
        )
        assertEquals(
            "Local agreement private key unavailable: Android Keystore unavailable (KeyStoreException)",
            privateUnavailableStatus
        )
        assertEquals("Local agreement public key unavailable.", publicUnavailableStatus)
    }

    @Test
    fun identityHandlerStatusDoesNotExposePrivateKeyBytes() {
        val keyPair = generateEcKeyPair()
        val privateKeyToken = keyPair.privateKeyBytes().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
        val status = auroraIdentityHandlerStatusText(
            loadResult = LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyUnavailable(
                reason = "Local agreement private key unavailable: Android Keystore unavailable (KeyStoreException)"
            ),
            isHandlerReady = false
        )

        assertFalse(status.contains(privateKeyToken))
    }

    @Test
    fun runtimeCreatesIdentityHandlerWhenLocalIdentityMaterialIsAvailable() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val registry = gr.hua.aurora.protocol.PeerSessionRegistry()
        val handler = createAuroraIdentityHandlerOrNull(
            stateHolder = holder,
            localIdentity = generateEcKeyPair().identity(),
            registry = registry
        )

        assertNotNull(handler)
    }

    @Test
    fun runtimeLeavesIdentityHandlerUnavailableWhenLocalIdentityMaterialIsMissing() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val registry = gr.hua.aurora.protocol.PeerSessionRegistry()
        val handler = createAuroraIdentityHandlerOrNull(
            stateHolder = holder,
            localIdentity = null,
            registry = registry
        )

        assertNull(handler)
    }

    @Test
    fun identityHandlerPromotesRecoveredPrivateChatAndRequestsSingleReplyWhenSessionWasMissing() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val local = generateEcKeyPair()
        val remote = generateEcKeyPair()
        val remotePublicKey = remote.publicKeyBytes()
        val canonicalRemotePeerId = gr.hua.aurora.protocol.PeerSessionPeerId
            .deriveFromPublicKey(remotePublicKey)
        holder.addOrUpdateContact(
            canonicalPeerId = canonicalRemotePeerId,
            displayName = "Alex"
        )
        var replyRequest: Pair<String, Boolean>? = null
        val registry = gr.hua.aurora.protocol.PeerSessionRegistry()
        val handler = requireNotNull(
            createAuroraIdentityHandlerOrNull(
                stateHolder = holder,
                localIdentity = local.identity(),
                registry = registry,
                onIdentityEstablished = { peerId, shouldReply ->
                    replyRequest = peerId to shouldReply
                }
            )
        )

        val result = handler(
            IncomingTransportMessage(
                frame = PeerIdentityExchangeMessage(
                    peerId = canonicalRemotePeerId,
                    publicAgreementKeyBytes = remotePublicKey,
                    createdAtMillis = 1_716_300_111L,
                    privateChatProposalId = "remote-proposal-1"
                ).toMessageFrame(frameId = "identity-runtime-recovery"),
                senderPublicKey = remotePublicKey
            )
        )

        assertEquals(
            PeerIdentityExchangeHandlingResult.Established(canonicalRemotePeerId),
            result
        )
        assertEquals(canonicalRemotePeerId to true, replyRequest)
        assertTrue(holder.findContactByPeerId(canonicalRemotePeerId)?.hasSession == true)
        assertEquals(
            "remote-proposal-1",
            holder.privateChatIdentityForPeerId(canonicalRemotePeerId)?.remoteProposalId
        )
    }

    @Test
    fun identityHandlerDoesNotRequestReplyWhenSessionAlreadyExists() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val local = generateEcKeyPair()
        val remote = generateEcKeyPair()
        val remotePublicKey = remote.publicKeyBytes()
        val canonicalRemotePeerId = gr.hua.aurora.protocol.PeerSessionPeerId
            .deriveFromPublicKey(remotePublicKey)
        val registry = gr.hua.aurora.protocol.PeerSessionRegistry().apply {
            val establishment = PeerSessionEstablisher.establishAndStore(
                localIdentity = local.identity(),
                remotePeerId = canonicalRemotePeerId,
                remotePeerPublicKeyBytes = remotePublicKey,
                registry = this
            )
            assertTrue(establishment is gr.hua.aurora.protocol.PeerSessionEstablishmentResult.Established)
        }
        var replyRequest: Pair<String, Boolean>? = null
        val handler = requireNotNull(
            createAuroraIdentityHandlerOrNull(
                stateHolder = holder,
                localIdentity = local.identity(),
                registry = registry,
                onIdentityEstablished = { peerId, shouldReply ->
                    replyRequest = peerId to shouldReply
                }
            )
        )

        handler(
            IncomingTransportMessage(
                frame = PeerIdentityExchangeMessage(
                    peerId = canonicalRemotePeerId,
                    publicAgreementKeyBytes = remotePublicKey,
                    createdAtMillis = 1_716_300_222L,
                    privateChatProposalId = "remote-proposal-2"
                ).toMessageFrame(frameId = "identity-runtime-existing-session"),
                senderPublicKey = remotePublicKey
            )
        )

        assertEquals(canonicalRemotePeerId to false, replyRequest)
    }

    @Test
    fun runtimeReceiverWithIdentityHandlerEstablishesSessionForPlaintextBootstrapFrame() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val local = generateEcKeyPair()
        val remote = generateEcKeyPair()
        val remotePublicKey = remote.publicKeyBytes()
        val canonicalRemotePeerId = gr.hua.aurora.protocol.PeerSessionPeerId
            .deriveFromPublicKey(remotePublicKey)
        val registry = gr.hua.aurora.protocol.PeerSessionRegistry()
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            sessionMaterialProvider = registry,
            handleIdentity = createAuroraIdentityHandlerOrNull(
                stateHolder = holder,
                localIdentity = local.identity(),
                registry = registry
            )
        )
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = MessageFrameCodec.encode(
                PeerIdentityExchangeMessage(
                    peerId = canonicalRemotePeerId,
                    publicAgreementKeyBytes = remotePublicKey,
                    createdAtMillis = 1_716_300_001L
                ).toMessageFrame(frameId = "identity-runtime")
            ).toByteArray(UTF_8),
            groupId = 0x4302
        )

        val result = frames.fold<BleGattTransportFrame, BleTransportReceiveResult?>(null) { _, frame ->
            receiver.receive(frame)
        } ?: error("Expected at least one transport frame.")

        assertTrue(result is BleTransportReceiveResult.Processed)
        val processed = result as BleTransportReceiveResult.Processed
        assertTrue(
            processed.processingResult is IncomingTransportFrameProcessingResult.IdentityHandled
        )
        val handled =
            processed.processingResult as IncomingTransportFrameProcessingResult.IdentityHandled
        assertEquals(
            PeerIdentityExchangeHandlingResult.Established(canonicalRemotePeerId),
            handled.handlingResult
        )
        val outgoingLookup = registry.lookupOutgoingMaterial(
            privateMessage(canonicalRemotePeerId)
        )
        assertTrue(outgoingLookup is OutgoingSessionMaterialLookupResult.Found)
    }

    @Test
    fun runtimeReceiverWithoutIdentityMaterialSurfacesHandlerUnavailable() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val remote = generateEcKeyPair()
        val registry = gr.hua.aurora.protocol.PeerSessionRegistry()
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            sessionMaterialProvider = registry,
            handleIdentity = createAuroraIdentityHandlerOrNull(
                stateHolder = holder,
                localIdentity = null,
                registry = registry
            )
        )
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = MessageFrameCodec.encode(
                PeerIdentityExchangeMessage(
                    peerId = "peer-runtime-unavailable",
                    publicAgreementKeyBytes = remote.publicKeyBytes(),
                    createdAtMillis = 1_716_300_002L
                ).toMessageFrame(frameId = "identity-runtime-unavailable")
            ).toByteArray(UTF_8),
            groupId = 0x4303
        )

        val result = frames.fold<BleGattTransportFrame, BleTransportReceiveResult?>(null) { _, frame ->
            receiver.receive(frame)
        } ?: error("Expected at least one transport frame.")

        assertTrue(result is BleTransportReceiveResult.Processed)
        val processed = result as BleTransportReceiveResult.Processed
        assertTrue(
            processed.processingResult
                is IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable
        )
    }

    @Test
    fun diagnosticsAfterEmptyInitialDecisionAreNoCandidates() {
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)

        val diagnostics = currentHybridBootstrapDiagnostics(provider)

        assertEquals(0, diagnostics.candidateCount)
        assertEquals(0, diagnostics.socketReadyCandidateCount)
        assertEquals(
            HybridBootstrapDiagnostics.SelectionStatus.NoCandidates,
            diagnostics.selectionStatus
        )
        assertEquals(null, diagnostics.selectedPeerId)
        assertEquals(null, diagnostics.selectedSessionId)
        assertEquals(null, diagnostics.selectedGroupOwnerAddress)
        assertEquals(null, diagnostics.selectedSocketPort)
        assertEquals(null, diagnostics.selectedLatestCreatedAtMillis)
    }

    @Test
    fun initialEmptyDecisionProducesNoCandidatesEndpointResolution() {
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)

        val resolution = currentHybridBootstrapSocketEndpointResolution(provider)

        assertEquals(
            HybridBootstrapSocketEndpointResolution.NoCandidates,
            resolution
        )
    }

    @Test
    fun initialEmptyDecisionProducesNoCandidatesAttemptDecision() {
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)

        val decision = currentHybridBootstrapAttemptDecision(
            provider = provider,
            requestedAtMillis = 1_716_380_001L
        )

        assertEquals(HybridBootstrapAttemptDecision.NoCandidates, decision)
    }

    @Test
    fun initialEmptyDecisionProducesNoCandidatesCommandBuildResult() {
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)

        val result = currentHybridBootstrapAttemptCommandBuildResult(
            provider = provider,
            requestedAtMillis = 1_716_390_001L,
            commandCreatedAtMillis = 1_716_390_002L
        )

        assertEquals(
            HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            result
        )
    }

    @Test
    fun runtimeWiringCreatesNoOpTriggerControllerWithoutTriggeringIt() {
        val controller = currentHybridBootstrapCommandTriggerController()

        assertNull(controller.latestResult)
        assertTrue(controller.triggerHistory.isEmpty())
    }

    @Test
    fun runtimeJavaNetHybridBootstrapGuardIsDisabled() {
        assertFalse(hybridBootstrapJavaNetRuntimeEnabled())
    }

    @Test
    fun runtimeExecutorModeResolvesToSocketPlanDisabledWhileJavaNetGuardIsOff() {
        assertEquals(
            HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED,
            currentHybridBootstrapRuntimeExecutorMode()
        )
    }

    @Test
    fun runtimeExecutorConfigExistsAndIsSocketPlanDisabled() {
        val config = currentHybridBootstrapCommandExecutorConfig()

        assertEquals(HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED, config.mode)
    }

    @Test
    fun runtimeStateExposesReadOnlyHybridBootstrapExecutorModeField() {
        val field = AuroraBleRuntimeState::class.java.getDeclaredField(
            "hybridBootstrapCommandExecutorMode"
        )

        assertEquals(HybridBootstrapCommandExecutorMode::class.java, field.type)
        assertTrue(Modifier.isFinal(field.modifiers))
    }

    @Test
    fun runtimeStateExposesReadOnlyHybridBootstrapJavaNetRuntimeEnabledField() {
        val field = AuroraBleRuntimeState::class.java.getDeclaredField(
            "hybridBootstrapJavaNetRuntimeEnabled"
        )

        assertEquals(Boolean::class.javaPrimitiveType, field.type)
        assertTrue(Modifier.isFinal(field.modifiers))
    }

    @Test
    fun runtimeExecutorConfigDefaultRejectionReasonIsHybridBootstrapExecutionIsDisabled() {
        val config = currentHybridBootstrapCommandExecutorConfig()

        assertEquals(
            "Hybrid bootstrap execution is disabled.",
            config.noOpRejectionReason
        )
    }

    @Test
    fun runtimeExecutorConfigDefaultDisabledSocketConnectorReasonIsPreserved() {
        val config = currentHybridBootstrapCommandExecutorConfig()

        assertEquals(
            "Hybrid bootstrap socket connector is disabled.",
            config.disabledSocketConnectorFailureReason
        )
    }

    @Test
    fun runtimeExecutorConfigMatchesExplicitSocketPlanDisabledFactoryConfig() {
        val config = currentHybridBootstrapCommandExecutorConfig()

        assertEquals(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED
            ),
            config
        )
    }

    @Test
    fun runtimeTriggerControllerMatchesFactoryCreateWithRuntimeConfigBehavior() {
        val controller = currentHybridBootstrapCommandTriggerController()
        val buildResult = HybridBootstrapAttemptCommandBuildResult.Built(
            HybridBootstrapAttemptCommand(
                peerId = "peer-runtime-config",
                sessionId = "session-runtime-config",
                bootstrapIdentifier = "bootstrap-runtime-config",
                groupOwnerAddress = "192.168.49.230",
                socketPort = 9230,
                latestCreatedAtMillis = 1_739_000_000L,
                requestedAtMillis = 1_739_000_001L,
                commandCreatedAtMillis = 1_739_000_002L
            )
        )
        val expected = HybridBootstrapCommandTriggerResult.Executed(
            executionResult = HybridBootstrapCommandExecutorFactory.create(
                currentHybridBootstrapCommandExecutorConfig()
            ).execute(buildResult.command)
        )

        val result = controller.trigger(buildResult)

        assertEquals(expected, result)
    }

    @Test
    fun latestExplicitTriggerResultIsInitiallyNull() {
        assertNull(initialHybridBootstrapCommandTriggerResult())
    }

    @Test
    fun runtimeInitializationDoesNotSetTriggerResult() {
        val latestTriggerResult = initialHybridBootstrapCommandTriggerResult()

        assertNull(latestTriggerResult)
    }

    @Test
    fun initialRuntimeManualTriggerSnapshotExists() {
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = initialHybridBootstrapCommandTriggerResult()
        )

        assertNotNull(snapshot)
    }

    @Test
    fun initialRuntimeManualTriggerSnapshotHasLatestTriggerResultNull() {
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = initialHybridBootstrapCommandTriggerResult()
        )

        assertNull(snapshot.latestTriggerResult)
        assertEquals(null, snapshot.triggerStatusText)
    }

    @Test
    fun initialRuntimeManualTriggerSnapshotHasCanTriggerNowFalseWhenCommandIsNotBuilt() {
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = initialHybridBootstrapCommandTriggerResult()
        )

        assertFalse(snapshot.canTriggerNow)
    }

    @Test
    fun runtimeInitializationDoesNotInvokeManualTriggerRequestCallback() {
        var invokeCount = 0
        val callback = createHybridBootstrapManualTriggerRequestCallback(
            guardedManualTriggerAction = {
                invokeCount += 1
                HybridBootstrapCommandTriggerResult.NoCandidates
            }
        )

        assertNotNull(callback)
        assertEquals(0, invokeCount)
    }

    @Test
    fun readingManualTriggerSnapshotDiagnosticsDoesNotInvokeManualTriggerRequestCallback() {
        var invokeCount = 0
        val callback = createHybridBootstrapManualTriggerRequestCallback(
            guardedManualTriggerAction = {
                invokeCount += 1
                HybridBootstrapCommandTriggerResult.NoCandidates
            }
        )
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = initialHybridBootstrapCommandTriggerResult()
        )

        assertFalse(snapshot.canTriggerNow)
        assertEquals("Hybrid bootstrap command: no candidates", snapshot.commandStatusText)
        assertNull(snapshot.triggerStatusText)
        assertNotNull(callback)
        assertEquals(0, invokeCount)
    }

    @Test
    fun noCandidatesDiagnosticsMapToStableRuntimeStatusText() {
        val diagnostics = HybridBootstrapDiagnostics(
            candidateCount = 0,
            socketReadyCandidateCount = 0,
            selectionStatus = HybridBootstrapDiagnostics.SelectionStatus.NoCandidates,
            selectedPeerId = null,
            selectedSessionId = null,
            selectedGroupOwnerAddress = null,
            selectedSocketPort = null,
            selectedLatestCreatedAtMillis = null,
            statusText = "No hybrid bootstrap candidates"
        )

        assertEquals(
            "Hybrid bootstrap: no candidates",
            hybridBootstrapDiagnosticsRuntimeStatusText(diagnostics)
        )
    }

    @Test
    fun noCandidatesEndpointResolutionMapsToStableRuntimeStatusText() {
        assertEquals(
            "Hybrid bootstrap endpoint: no candidates",
            hybridBootstrapSocketEndpointRuntimeStatusText(
                HybridBootstrapSocketEndpointResolution.NoCandidates
            )
        )
    }

    @Test
    fun noCandidatesAttemptDecisionMapsToStableRuntimeStatusText() {
        assertEquals(
            "Hybrid bootstrap attempt: no candidates",
            hybridBootstrapAttemptRuntimeStatusText(
                HybridBootstrapAttemptDecision.NoCandidates
            )
        )
    }

    @Test
    fun noCandidatesCommandBuildResultMapsToStableRuntimeStatusText() {
        assertEquals(
            "Hybrid bootstrap command: no candidates",
            hybridBootstrapAttemptCommandBuildRuntimeStatusText(
                HybridBootstrapAttemptCommandBuildResult.NoCandidates
            )
        )
    }

    @Test
    fun runtimeReceiverRecordsHybridControlFrameAndProviderComputesSocketReadyDecisionFromSameStore() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-session-1",
                    createdAtMillis = 1_716_350_001L,
                    groupOwnerAddress = "192.168.49.20",
                    socketPort = 8988
                ),
                frameId = "hybrid-control-runtime-1",
                senderId = "peer-hybrid"
            )
        )
        val decision = requireNotNull(
            hybridBootstrapDecisionAfterReceiveOrNull(
                result = result,
                provider = provider
            )
        )

        assertTrue(result is BleTransportReceiveResult.Processed)
        val processed = result as BleTransportReceiveResult.Processed
        assertTrue(
            processed.processingResult is IncomingTransportFrameProcessingResult.HybridControlHandled
        )
        assertEquals(1, decision.candidates.size)
        assertEquals("peer-hybrid", decision.candidates.single().peerId)
        assertTrue(decision.candidates.single().socketReady)
        assertEquals(
            HybridBootstrapCandidateSelection.Selected(decision.candidates.single()),
            decision.selection
        )
    }

    @Test
    fun diagnosticsAfterHybridControlHandledWithOfferOnlyStateBecomeNoSocketReadyCandidates() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridOfferMessage(
                    sessionId = "hybrid-diagnostics-session-1",
                    createdAtMillis = 1_716_360_001L
                ),
                frameId = "hybrid-diagnostics-offer-only",
                senderId = "peer-hybrid-diagnostics-1"
            )
        )
        val diagnostics = requireNotNull(
            hybridBootstrapDiagnosticsAfterReceiveOrNull(
                result = result,
                provider = provider
            )
        )

        assertEquals(1, diagnostics.candidateCount)
        assertEquals(0, diagnostics.socketReadyCandidateCount)
        assertEquals(
            HybridBootstrapDiagnostics.SelectionStatus.NoSocketReadyCandidates,
            diagnostics.selectionStatus
        )
        assertEquals(null, diagnostics.selectedPeerId)
    }

    @Test
    fun offerOnlyHybridControlStateProducesNoSocketReadyCandidateEndpointResolution() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridOfferMessage(
                    sessionId = "hybrid-endpoint-session-1",
                    createdAtMillis = 1_716_370_001L
                ),
                frameId = "hybrid-endpoint-offer-only",
                senderId = "peer-hybrid-endpoint-1"
            )
        )
        val resolution = requireNotNull(
            hybridBootstrapSocketEndpointResolutionAfterReceiveOrNull(
                result = result,
                provider = provider
            )
        )

        assertEquals(
            HybridBootstrapSocketEndpointResolution.NoSocketReadyCandidate,
            resolution
        )
    }

    @Test
    fun offerOnlyHybridControlStateProducesNoSocketReadyCandidateAttemptDecision() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridOfferMessage(
                    sessionId = "hybrid-attempt-session-1",
                    createdAtMillis = 1_716_380_010L
                ),
                frameId = "hybrid-attempt-offer-only",
                senderId = "peer-hybrid-attempt-1"
            )
        )
        val decision = requireNotNull(
            hybridBootstrapAttemptDecisionAfterReceiveOrNull(
                result = result,
                provider = provider,
                requestedAtMillis = 1_716_380_020L
            )
        )

        assertEquals(
            HybridBootstrapAttemptDecision.NoSocketReadyCandidate,
            decision
        )
    }

    @Test
    fun offerOnlyHybridControlStateProducesNoSocketReadyCandidateCommandBuildResult() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridOfferMessage(
                    sessionId = "hybrid-command-session-1",
                    createdAtMillis = 1_716_390_010L
                ),
                frameId = "hybrid-command-offer-only",
                senderId = "peer-hybrid-command-1"
            )
        )
        val buildResult = requireNotNull(
            hybridBootstrapAttemptCommandBuildResultAfterReceiveOrNull(
                result = result,
                provider = provider,
                requestedAtMillis = 1_716_390_020L,
                commandCreatedAtMillis = 1_716_390_021L
            )
        )

        assertEquals(
            HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate,
            buildResult
        )
    }

    @Test
    fun noSocketReadyCandidatesDiagnosticsMapToStableRuntimeStatusText() {
        val diagnostics = HybridBootstrapDiagnostics(
            candidateCount = 2,
            socketReadyCandidateCount = 0,
            selectionStatus = HybridBootstrapDiagnostics.SelectionStatus.NoSocketReadyCandidates,
            selectedPeerId = null,
            selectedSessionId = null,
            selectedGroupOwnerAddress = null,
            selectedSocketPort = null,
            selectedLatestCreatedAtMillis = null,
            statusText = "Hybrid bootstrap candidates available, none socket-ready"
        )

        assertEquals(
            "Hybrid bootstrap: candidates available, none socket-ready",
            hybridBootstrapDiagnosticsRuntimeStatusText(diagnostics)
        )
    }

    @Test
    fun noSocketReadyCandidateEndpointResolutionMapsToStableRuntimeStatusText() {
        assertEquals(
            "Hybrid bootstrap endpoint: no socket-ready candidate",
            hybridBootstrapSocketEndpointRuntimeStatusText(
                HybridBootstrapSocketEndpointResolution.NoSocketReadyCandidate
            )
        )
    }

    @Test
    fun noSocketReadyCandidateAttemptDecisionMapsToStableRuntimeStatusText() {
        assertEquals(
            "Hybrid bootstrap attempt: no socket-ready candidate",
            hybridBootstrapAttemptRuntimeStatusText(
                HybridBootstrapAttemptDecision.NoSocketReadyCandidate
            )
        )
    }

    @Test
    fun noSocketReadyCandidateCommandBuildResultMapsToStableRuntimeStatusText() {
        assertEquals(
            "Hybrid bootstrap command: no socket-ready candidate",
            hybridBootstrapAttemptCommandBuildRuntimeStatusText(
                HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate
            )
        )
    }

    @Test
    fun diagnosticsAfterHybridControlHandledWithSocketReadyStateBecomeSelected() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-diagnostics-session-2",
                    createdAtMillis = 1_716_360_002L,
                    groupOwnerAddress = "192.168.49.42",
                    socketPort = 9042
                ),
                frameId = "hybrid-diagnostics-socket-ready",
                senderId = "peer-hybrid-diagnostics-2"
            )
        )
        val diagnostics = requireNotNull(
            hybridBootstrapDiagnosticsAfterReceiveOrNull(
                result = result,
                provider = provider
            )
        )

        assertEquals(
            HybridBootstrapDiagnostics.SelectionStatus.Selected,
            diagnostics.selectionStatus
        )
        assertEquals("peer-hybrid-diagnostics-2", diagnostics.selectedPeerId)
        assertEquals("hybrid-diagnostics-session-2", diagnostics.selectedSessionId)
        assertEquals("192.168.49.42", diagnostics.selectedGroupOwnerAddress)
        assertEquals(9042, diagnostics.selectedSocketPort)
        assertEquals(1_716_360_002L, diagnostics.selectedLatestCreatedAtMillis)
    }

    @Test
    fun socketReadyHybridControlStateProducesResolvedEndpoint() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-endpoint-session-2",
                    createdAtMillis = 1_716_370_002L,
                    groupOwnerAddress = "192.168.49.52",
                    socketPort = 9052
                ),
                frameId = "hybrid-endpoint-socket-ready",
                senderId = "peer-hybrid-endpoint-2"
            )
        )
        val resolution = requireNotNull(
            hybridBootstrapSocketEndpointResolutionAfterReceiveOrNull(
                result = result,
                provider = provider
            )
        )

        assertEquals(
            HybridBootstrapSocketEndpointResolution.Resolved(
                HybridBootstrapSocketEndpoint(
                    peerId = "peer-hybrid-endpoint-2",
                    sessionId = "hybrid-endpoint-session-2",
                    bootstrapIdentifier = "hybrid-endpoint-session-2",
                    groupOwnerAddress = "192.168.49.52",
                    socketPort = 9052,
                    latestCreatedAtMillis = 1_716_370_002L
                )
            ),
            resolution
        )
    }

    @Test
    fun socketReadyHybridControlStateProducesAllowedAttemptDecision() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-attempt-session-2",
                    createdAtMillis = 1_716_380_030L,
                    groupOwnerAddress = "192.168.49.62",
                    socketPort = 9062
                ),
                frameId = "hybrid-attempt-socket-ready",
                senderId = "peer-hybrid-attempt-2"
            )
        )
        val decision = requireNotNull(
            hybridBootstrapAttemptDecisionAfterReceiveOrNull(
                result = result,
                provider = provider,
                requestedAtMillis = 1_716_380_040L
            )
        )

        assertEquals(
            HybridBootstrapAttemptDecision.Allowed(
                HybridBootstrapAttemptRequest(
                    peerId = "peer-hybrid-attempt-2",
                    sessionId = "hybrid-attempt-session-2",
                    bootstrapIdentifier = "hybrid-attempt-session-2",
                    groupOwnerAddress = "192.168.49.62",
                    socketPort = 9062,
                    latestCreatedAtMillis = 1_716_380_030L,
                    requestedAtMillis = 1_716_380_040L
                )
            ),
            decision
        )
    }

    @Test
    fun socketReadyHybridControlStateProducesBuiltCommandBuildResult() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-command-session-2",
                    createdAtMillis = 1_716_390_030L,
                    groupOwnerAddress = "192.168.49.72",
                    socketPort = 9072
                ),
                frameId = "hybrid-command-socket-ready",
                senderId = "peer-hybrid-command-2"
            )
        )
        val buildResult = requireNotNull(
            hybridBootstrapAttemptCommandBuildResultAfterReceiveOrNull(
                result = result,
                provider = provider,
                requestedAtMillis = 1_716_390_040L,
                commandCreatedAtMillis = 1_716_390_041L
            )
        )

        assertEquals(
            HybridBootstrapAttemptCommandBuildResult.Built(
                HybridBootstrapAttemptCommand(
                    peerId = "peer-hybrid-command-2",
                    sessionId = "hybrid-command-session-2",
                    bootstrapIdentifier = "hybrid-command-session-2",
                    groupOwnerAddress = "192.168.49.72",
                    socketPort = 9072,
                    latestCreatedAtMillis = 1_716_390_030L,
                    requestedAtMillis = 1_716_390_040L,
                    commandCreatedAtMillis = 1_716_390_041L
                )
            ),
            buildResult
        )
    }

    @Test
    fun selectedDiagnosticsMapToStableRuntimeStatusTextWithPeerSessionAddressAndPort() {
        val diagnostics = HybridBootstrapDiagnostics(
            candidateCount = 1,
            socketReadyCandidateCount = 1,
            selectionStatus = HybridBootstrapDiagnostics.SelectionStatus.Selected,
            selectedPeerId = "peer-selected",
            selectedSessionId = "session-selected",
            selectedGroupOwnerAddress = "192.168.49.50",
            selectedSocketPort = 9050,
            selectedLatestCreatedAtMillis = 1_716_360_050L,
            statusText = "Hybrid bootstrap candidate ready: peer=peer-selected session=session-selected address=192.168.49.50 port=9050"
        )

        assertEquals(
            "Hybrid bootstrap: socket-ready peer=peer-selected session=session-selected address=192.168.49.50 port=9050",
            hybridBootstrapDiagnosticsRuntimeStatusText(diagnostics)
        )
    }

    @Test
    fun resolvedEndpointRuntimeStatusTextPreservesPeerSessionAddressAndPort() {
        val resolution = HybridBootstrapSocketEndpointResolution.Resolved(
            HybridBootstrapSocketEndpoint(
                peerId = "peer-endpoint-selected",
                sessionId = "session-endpoint-selected",
                bootstrapIdentifier = "bootstrap-endpoint-selected",
                groupOwnerAddress = "192.168.49.53",
                socketPort = 9053,
                latestCreatedAtMillis = 1_716_370_053L
            )
        )

        assertEquals(
            "Hybrid bootstrap endpoint: peer=peer-endpoint-selected session=session-endpoint-selected address=192.168.49.53 port=9053",
            hybridBootstrapSocketEndpointRuntimeStatusText(resolution)
        )
    }

    @Test
    fun allowedAttemptDecisionRuntimeStatusTextIsStableAndPreservesPeerSessionAddressAndPort() {
        val decision = HybridBootstrapAttemptDecision.Allowed(
            HybridBootstrapAttemptRequest(
                peerId = "peer-attempt-selected",
                sessionId = "session-attempt-selected",
                bootstrapIdentifier = "bootstrap-attempt-selected",
                groupOwnerAddress = "192.168.49.63",
                socketPort = 9063,
                latestCreatedAtMillis = 1_716_380_063L,
                requestedAtMillis = 1_716_380_064L
            )
        )

        assertEquals(
            "Hybrid bootstrap attempt: allowed peer=peer-attempt-selected session=session-attempt-selected address=192.168.49.63 port=9063",
            hybridBootstrapAttemptRuntimeStatusText(decision)
        )
    }

    @Test
    fun builtCommandBuildStatusTextIsStableAndPreservesPeerSessionAddressAndPort() {
        val result = HybridBootstrapAttemptCommandBuildResult.Built(
            HybridBootstrapAttemptCommand(
                peerId = "peer-command-selected",
                sessionId = "session-command-selected",
                bootstrapIdentifier = "bootstrap-command-selected",
                groupOwnerAddress = "192.168.49.73",
                socketPort = 9073,
                latestCreatedAtMillis = 1_716_390_073L,
                requestedAtMillis = 1_716_390_074L,
                commandCreatedAtMillis = 1_716_390_075L
            )
        )

        assertEquals(
            "Hybrid bootstrap command: built peer=peer-command-selected session=session-command-selected address=192.168.49.73 port=9073",
            hybridBootstrapAttemptCommandBuildRuntimeStatusText(result)
        )
    }

    @Test
    fun acceptedTriggerStatusTextIsStable() {
        val result = HybridBootstrapCommandTriggerResult.Executed(
            executionResult = HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-trigger-accepted",
                sessionId = "session-trigger-accepted",
                bootstrapIdentifier = "bootstrap-trigger-accepted",
                groupOwnerAddress = "192.168.49.171",
                socketPort = 9171,
                commandCreatedAtMillis = 1_733_000_010L
            )
        )

        assertEquals(
            "Hybrid bootstrap trigger: accepted peer=peer-trigger-accepted session=session-trigger-accepted address=192.168.49.171 port=9171",
            hybridBootstrapCommandTriggerRuntimeStatusText(result)
        )
    }

    @Test
    fun rejectedTriggerStatusTextIsStable() {
        val result = HybridBootstrapCommandTriggerResult.Executed(
            executionResult = HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap execution is disabled."
            )
        )

        assertEquals(
            "Hybrid bootstrap trigger: rejected: Hybrid bootstrap execution is disabled.",
            hybridBootstrapCommandTriggerRuntimeStatusText(result)
        )
    }

    @Test
    fun noCandidatesTriggerStatusTextIsStable() {
        assertEquals(
            "Hybrid bootstrap trigger: no candidates",
            hybridBootstrapCommandTriggerRuntimeStatusText(
                HybridBootstrapCommandTriggerResult.NoCandidates
            )
        )
    }

    @Test
    fun noSocketReadyCandidateTriggerStatusTextIsStable() {
        assertEquals(
            "Hybrid bootstrap trigger: no socket-ready candidate",
            hybridBootstrapCommandTriggerRuntimeStatusText(
                HybridBootstrapCommandTriggerResult.NoSocketReadyCandidate
            )
        )
    }

    @Test
    fun invalidEndpointTriggerStatusTextIsStable() {
        val result = HybridBootstrapCommandTriggerResult.InvalidEndpoint(
            reason = "Endpoint timestamp is in the future."
        )

        assertEquals(
            "Hybrid bootstrap trigger: invalid endpoint: Endpoint timestamp is in the future.",
            hybridBootstrapCommandTriggerRuntimeStatusText(result)
        )
    }

    @Test
    fun endpointTooOldTriggerStatusTextIsStable() {
        val result = HybridBootstrapCommandTriggerResult.EndpointTooOld(
            ageMillis = 45_000L,
            maxAgeMillis = 30_000L
        )

        assertEquals(
            "Hybrid bootstrap trigger: endpoint too old age=45000 max=30000",
            hybridBootstrapCommandTriggerRuntimeStatusText(result)
        )
    }

    @Test
    fun notAllowedTriggerStatusTextIsStable() {
        val result = HybridBootstrapCommandTriggerResult.NotAllowed(
            reason = "Command creation timestamp is before request timestamp."
        )

        assertEquals(
            "Hybrid bootstrap trigger: not allowed: Command creation timestamp is before request timestamp.",
            hybridBootstrapCommandTriggerRuntimeStatusText(result)
        )
    }

    @Test
    fun repeatedHybridControlFramesUpdateProviderDecisionThroughExistingStoreSnapshot() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val firstResult = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridOfferMessage(
                    sessionId = "hybrid-session-2",
                    createdAtMillis = 1_716_350_010L
                ),
                frameId = "hybrid-control-runtime-2-offer",
                senderId = "peer-hybrid-2"
            )
        )
        val firstDecision = requireNotNull(
            hybridBootstrapDecisionAfterReceiveOrNull(
                result = firstResult,
                provider = provider
            )
        )

        assertEquals(
            HybridBootstrapCandidateSelection.NoSocketReadyCandidates,
            firstDecision.selection
        )
        assertEquals(1, firstDecision.candidates.size)
        assertFalse(firstDecision.candidates.single().socketReady)

        val secondResult = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-session-2",
                    createdAtMillis = 1_716_350_011L,
                    groupOwnerAddress = "192.168.49.21",
                    socketPort = 8989
                ),
                frameId = "hybrid-control-runtime-2-socket",
                senderId = "peer-hybrid-2"
            )
        )
        val secondDecision = requireNotNull(
            hybridBootstrapDecisionAfterReceiveOrNull(
                result = secondResult,
                provider = provider
            )
        )

        assertEquals(1, secondDecision.candidates.size)
        assertTrue(secondDecision.candidates.single().hasOffer)
        assertTrue(secondDecision.candidates.single().hasSocketHint)
        assertTrue(secondDecision.candidates.single().socketReady)
        assertEquals(
            HybridBootstrapCandidateSelection.Selected(secondDecision.candidates.single()),
            secondDecision.selection
        )
    }

    @Test
    fun diagnosticsUpdateWhenRepeatedHybridControlFramesUpdateTheDecision() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val firstResult = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridOfferMessage(
                    sessionId = "hybrid-diagnostics-session-3",
                    createdAtMillis = 1_716_360_010L
                ),
                frameId = "hybrid-diagnostics-update-offer",
                senderId = "peer-hybrid-diagnostics-3"
            )
        )
        val firstDiagnostics = requireNotNull(
            hybridBootstrapDiagnosticsAfterReceiveOrNull(
                result = firstResult,
                provider = provider
            )
        )

        assertEquals(
            HybridBootstrapDiagnostics.SelectionStatus.NoSocketReadyCandidates,
            firstDiagnostics.selectionStatus
        )

        val secondResult = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-diagnostics-session-3",
                    createdAtMillis = 1_716_360_011L,
                    groupOwnerAddress = "192.168.49.43",
                    socketPort = 9043
                ),
                frameId = "hybrid-diagnostics-update-socket",
                senderId = "peer-hybrid-diagnostics-3"
            )
        )
        val secondDiagnostics = requireNotNull(
            hybridBootstrapDiagnosticsAfterReceiveOrNull(
                result = secondResult,
                provider = provider
            )
        )

        assertEquals(
            HybridBootstrapDiagnostics.SelectionStatus.Selected,
            secondDiagnostics.selectionStatus
        )
        assertEquals(1, secondDiagnostics.socketReadyCandidateCount)
        assertEquals("peer-hybrid-diagnostics-3", secondDiagnostics.selectedPeerId)
    }

    @Test
    fun repeatedHybridControlFramesUpdateEndpointResolutionThroughLatestDecision() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val firstResult = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridOfferMessage(
                    sessionId = "hybrid-endpoint-session-3",
                    createdAtMillis = 1_716_370_010L
                ),
                frameId = "hybrid-endpoint-update-offer",
                senderId = "peer-hybrid-endpoint-3"
            )
        )
        val firstResolution = requireNotNull(
            hybridBootstrapSocketEndpointResolutionAfterReceiveOrNull(
                result = firstResult,
                provider = provider
            )
        )

        assertEquals(
            HybridBootstrapSocketEndpointResolution.NoSocketReadyCandidate,
            firstResolution
        )

        val secondResult = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-endpoint-session-3",
                    createdAtMillis = 1_716_370_011L,
                    groupOwnerAddress = "192.168.49.54",
                    socketPort = 9054
                ),
                frameId = "hybrid-endpoint-update-socket",
                senderId = "peer-hybrid-endpoint-3"
            )
        )
        val secondResolution = requireNotNull(
            hybridBootstrapSocketEndpointResolutionAfterReceiveOrNull(
                result = secondResult,
                provider = provider
            )
        )

        assertEquals(
            HybridBootstrapSocketEndpointResolution.Resolved(
                HybridBootstrapSocketEndpoint(
                    peerId = "peer-hybrid-endpoint-3",
                    sessionId = "hybrid-endpoint-session-3",
                    bootstrapIdentifier = "hybrid-endpoint-session-3",
                    groupOwnerAddress = "192.168.49.54",
                    socketPort = 9054,
                    latestCreatedAtMillis = 1_716_370_011L
                )
            ),
            secondResolution
        )
    }

    @Test
    fun repeatedHybridControlFramesUpdateAttemptDecisionThroughLatestEndpointResolution() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val firstResult = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridOfferMessage(
                    sessionId = "hybrid-attempt-session-3",
                    createdAtMillis = 1_716_380_050L
                ),
                frameId = "hybrid-attempt-update-offer",
                senderId = "peer-hybrid-attempt-3"
            )
        )
        val firstDecision = requireNotNull(
            hybridBootstrapAttemptDecisionAfterReceiveOrNull(
                result = firstResult,
                provider = provider,
                requestedAtMillis = 1_716_380_060L
            )
        )

        assertEquals(
            HybridBootstrapAttemptDecision.NoSocketReadyCandidate,
            firstDecision
        )

        val secondResult = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-attempt-session-3",
                    createdAtMillis = 1_716_380_061L,
                    groupOwnerAddress = "192.168.49.64",
                    socketPort = 9064
                ),
                frameId = "hybrid-attempt-update-socket",
                senderId = "peer-hybrid-attempt-3"
            )
        )
        val secondDecision = requireNotNull(
            hybridBootstrapAttemptDecisionAfterReceiveOrNull(
                result = secondResult,
                provider = provider,
                requestedAtMillis = 1_716_380_071L
            )
        )

        assertEquals(
            HybridBootstrapAttemptDecision.Allowed(
                HybridBootstrapAttemptRequest(
                    peerId = "peer-hybrid-attempt-3",
                    sessionId = "hybrid-attempt-session-3",
                    bootstrapIdentifier = "hybrid-attempt-session-3",
                    groupOwnerAddress = "192.168.49.64",
                    socketPort = 9064,
                    latestCreatedAtMillis = 1_716_380_061L,
                    requestedAtMillis = 1_716_380_071L
                )
            ),
            secondDecision
        )
    }

    @Test
    fun repeatedHybridControlFramesUpdateCommandBuildResultThroughLatestAttemptDecision() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val firstResult = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridOfferMessage(
                    sessionId = "hybrid-command-session-3",
                    createdAtMillis = 1_716_390_050L
                ),
                frameId = "hybrid-command-update-offer",
                senderId = "peer-hybrid-command-3"
            )
        )
        val firstBuildResult = requireNotNull(
            hybridBootstrapAttemptCommandBuildResultAfterReceiveOrNull(
                result = firstResult,
                provider = provider,
                requestedAtMillis = 1_716_390_060L,
                commandCreatedAtMillis = 1_716_390_061L
            )
        )

        assertEquals(
            HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate,
            firstBuildResult
        )

        val secondResult = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-command-session-3",
                    createdAtMillis = 1_716_390_062L,
                    groupOwnerAddress = "192.168.49.74",
                    socketPort = 9074
                ),
                frameId = "hybrid-command-update-socket",
                senderId = "peer-hybrid-command-3"
            )
        )
        val secondBuildResult = requireNotNull(
            hybridBootstrapAttemptCommandBuildResultAfterReceiveOrNull(
                result = secondResult,
                provider = provider,
                requestedAtMillis = 1_716_390_072L,
                commandCreatedAtMillis = 1_716_390_073L
            )
        )

        assertEquals(
            HybridBootstrapAttemptCommandBuildResult.Built(
                HybridBootstrapAttemptCommand(
                    peerId = "peer-hybrid-command-3",
                    sessionId = "hybrid-command-session-3",
                    bootstrapIdentifier = "hybrid-command-session-3",
                    groupOwnerAddress = "192.168.49.74",
                    socketPort = 9074,
                    latestCreatedAtMillis = 1_716_390_062L,
                    requestedAtMillis = 1_716_390_072L,
                    commandCreatedAtMillis = 1_716_390_073L
                )
            ),
            secondBuildResult
        )
    }

    @Test
    fun hybridControlHandledUpdatesCommandBuildResultButDoesNotTriggerController() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val controller = currentHybridBootstrapCommandTriggerController()
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridOfferMessage(
                    sessionId = "hybrid-command-no-trigger-1",
                    createdAtMillis = 1_733_000_020L
                ),
                frameId = "hybrid-command-no-trigger-offer",
                senderId = "peer-hybrid-command-no-trigger-1"
            )
        )

        assertEquals(
            HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate,
            requireNotNull(
                hybridBootstrapAttemptCommandBuildResultAfterReceiveOrNull(
                    result = result,
                    provider = provider,
                    requestedAtMillis = 1_733_000_021L,
                    commandCreatedAtMillis = 1_733_000_022L
                )
            )
        )
        assertNull(controller.latestResult)
        assertTrue(controller.triggerHistory.isEmpty())
    }

    @Test
    fun hybridControlHandledDoesNotSetTriggerResult() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        var latestTriggerResult = initialHybridBootstrapCommandTriggerResult()

        receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridOfferMessage(
                    sessionId = "hybrid-trigger-state-offer",
                    createdAtMillis = 1_733_100_020L
                ),
                frameId = "hybrid-trigger-state-offer",
                senderId = "peer-hybrid-trigger-state-offer"
            )
        )

        assertNull(latestTriggerResult)
    }

    @Test
    fun socketReadyCommandBuildResultStillDoesNotTriggerControllerAutomatically() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val controller = currentHybridBootstrapCommandTriggerController()
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-command-no-trigger-2",
                    createdAtMillis = 1_733_000_030L,
                    groupOwnerAddress = "192.168.49.172",
                    socketPort = 9172
                ),
                frameId = "hybrid-command-no-trigger-socket",
                senderId = "peer-hybrid-command-no-trigger-2"
            )
        )

        assertEquals(
            HybridBootstrapAttemptCommandBuildResult.Built(
                HybridBootstrapAttemptCommand(
                    peerId = "peer-hybrid-command-no-trigger-2",
                    sessionId = "hybrid-command-no-trigger-2",
                    bootstrapIdentifier = "hybrid-command-no-trigger-2",
                    groupOwnerAddress = "192.168.49.172",
                    socketPort = 9172,
                    latestCreatedAtMillis = 1_733_000_030L,
                    requestedAtMillis = 1_733_000_031L,
                    commandCreatedAtMillis = 1_733_000_032L
                )
            ),
            requireNotNull(
                hybridBootstrapAttemptCommandBuildResultAfterReceiveOrNull(
                    result = result,
                    provider = provider,
                    requestedAtMillis = 1_733_000_031L,
                    commandCreatedAtMillis = 1_733_000_032L
                )
            )
        )
        assertNull(controller.latestResult)
        assertTrue(controller.triggerHistory.isEmpty())
    }

    @Test
    fun socketReadyCommandBuildResultDoesNotSetTriggerResultAutomatically() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        var latestTriggerResult = initialHybridBootstrapCommandTriggerResult()

        receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-trigger-state-socket",
                    createdAtMillis = 1_733_100_030L,
                    groupOwnerAddress = "192.168.49.179",
                    socketPort = 9179
                ),
                frameId = "hybrid-trigger-state-socket",
                senderId = "peer-hybrid-trigger-state-socket"
            )
        )

        assertNull(latestTriggerResult)
    }

    @Test
    fun hybridControlHandledRefreshesManualTriggerSnapshotFromUpdatedCommandBuildResult() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        var latestTriggerResult = initialHybridBootstrapCommandTriggerResult()

        val receiveResult = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-manual-snapshot-socket",
                    createdAtMillis = 1_733_100_031L,
                    groupOwnerAddress = "192.168.49.180",
                    socketPort = 9180
                ),
                frameId = "hybrid-manual-snapshot-socket",
                senderId = "peer-hybrid-manual-snapshot-socket"
            )
        )
        val commandBuildResult = requireNotNull(
            hybridBootstrapAttemptCommandBuildResultAfterReceiveOrNull(
                result = receiveResult,
                provider = provider,
                requestedAtMillis = 1_733_100_032L,
                commandCreatedAtMillis = 1_733_100_033L
            )
        )

        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = commandBuildResult,
            latestTriggerResult = latestTriggerResult
        )

        assertTrue(snapshot.canTriggerNow)
        assertEquals(commandBuildResult, snapshot.commandBuildResult)
        assertEquals(
            "Hybrid bootstrap command: built peer=peer-hybrid-manual-snapshot-socket session=hybrid-manual-snapshot-socket address=192.168.49.180 port=9180",
            snapshot.commandStatusText
        )
        assertNull(snapshot.latestTriggerResult)
        assertNull(snapshot.triggerStatusText)
    }

    @Test
    fun hybridControlHandledDoesNotInvokeManualTriggerRequestCallback() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN2"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        var callbackInvokeCount = 0
        val callback = createHybridBootstrapManualTriggerRequestCallback(
            guardedManualTriggerAction = {
                callbackInvokeCount += 1
                HybridBootstrapCommandTriggerResult.NoCandidates
            }
        )

        val receiveResult = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-manual-callback-socket",
                    createdAtMillis = 1_733_100_041L,
                    groupOwnerAddress = "192.168.49.181",
                    socketPort = 9181
                ),
                frameId = "hybrid-manual-callback-socket",
                senderId = "peer-hybrid-manual-callback-socket"
            )
        )
        val commandBuildResult = hybridBootstrapAttemptCommandBuildResultAfterReceiveOrNull(
            result = receiveResult,
            provider = provider,
            requestedAtMillis = 1_733_100_042L,
            commandCreatedAtMillis = 1_733_100_043L
        )

        assertNotNull(callback)
        assertTrue(commandBuildResult is HybridBootstrapAttemptCommandBuildResult.Built)
        assertEquals(0, callbackInvokeCount)
    }

    @Test
    fun nonBuiltCommandBuildResultMakesManualTriggerSnapshotCanTriggerNowFalse() {
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate,
            latestTriggerResult = initialHybridBootstrapCommandTriggerResult()
        )

        assertFalse(snapshot.canTriggerNow)
        assertEquals(
            "Hybrid bootstrap command: no socket-ready candidate",
            snapshot.commandStatusText
        )
    }

    @Test
    fun disabledSocketExecutorWouldRejectIfExplicitlyTriggeredButRuntimeDoesNotTriggerItAutomatically() {
        val controller = currentHybridBootstrapCommandTriggerController()

        val result = triggerHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.Built(
                HybridBootstrapAttemptCommand(
                    peerId = "peer-explicit-no-op",
                    sessionId = "session-explicit-no-op",
                    bootstrapIdentifier = "bootstrap-explicit-no-op",
                    groupOwnerAddress = "192.168.49.173",
                    socketPort = 9173,
                    latestCreatedAtMillis = 1_733_000_040L,
                    requestedAtMillis = 1_733_000_041L,
                    commandCreatedAtMillis = 1_733_000_042L
                )
            ),
            controller = controller
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.Executed(
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Hybrid bootstrap socket connector is disabled."
                )
            ),
            result
        )
    }

    @Test
    fun explicitHelperWithBuiltBuildResultTriggersControllerAndReturnsExecuted() {
        val controller = currentHybridBootstrapCommandTriggerController()

        val result = triggerHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.Built(
                HybridBootstrapAttemptCommand(
                    peerId = "peer-explicit-helper",
                    sessionId = "session-explicit-helper",
                    bootstrapIdentifier = "bootstrap-explicit-helper",
                    groupOwnerAddress = "192.168.49.175",
                    socketPort = 9175,
                    latestCreatedAtMillis = 1_733_000_060L,
                    requestedAtMillis = 1_733_000_061L,
                    commandCreatedAtMillis = 1_733_000_062L
                )
            ),
            controller = controller
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.Executed(
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Hybrid bootstrap socket connector is disabled."
                )
            ),
            result
        )
    }

    @Test
    fun explicitHelperRecordsLatestResultInController() {
        val controller = currentHybridBootstrapCommandTriggerController()
        val expected = HybridBootstrapCommandTriggerResult.NoCandidates

        val result = triggerHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            controller = controller
        )

        assertEquals(expected, result)
        assertEquals(expected, controller.latestResult)
    }

    @Test
    fun explicitHelperRecordsTriggerHistoryInController() {
        val controller = currentHybridBootstrapCommandTriggerController()
        val first = triggerHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            controller = controller
        )
        val second = triggerHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate,
            controller = controller
        )

        assertEquals(listOf(first, second), controller.triggerHistory)
    }

    @Test
    fun explicitHelperWithNoCandidatesReturnsNoCandidates() {
        val controller = currentHybridBootstrapCommandTriggerController()

        val result = triggerHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            controller = controller
        )

        assertEquals(HybridBootstrapCommandTriggerResult.NoCandidates, result)
    }

    @Test
    fun explicitHelperWithNoSocketReadyCandidateReturnsNoSocketReadyCandidate() {
        val controller = currentHybridBootstrapCommandTriggerController()

        val result = triggerHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate,
            controller = controller
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.NoSocketReadyCandidate,
            result
        )
    }

    @Test
    fun explicitHelperWithInvalidEndpointPreservesReason() {
        val controller = currentHybridBootstrapCommandTriggerController()

        val result = triggerHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            ),
            controller = controller
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            ),
            result
        )
    }

    @Test
    fun explicitHelperWithEndpointTooOldPreservesAgeAndMax() {
        val controller = currentHybridBootstrapCommandTriggerController()

        val result = triggerHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.EndpointTooOld(
                ageMillis = 45_000L,
                maxAgeMillis = 30_000L
            ),
            controller = controller
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.EndpointTooOld(
                ageMillis = 45_000L,
                maxAgeMillis = 30_000L
            ),
            result
        )
    }

    @Test
    fun explicitHelperWithNotAllowedPreservesReason() {
        val controller = currentHybridBootstrapCommandTriggerController()

        val result = triggerHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NotAllowed(
                reason = "Command creation timestamp is before request timestamp."
            ),
            controller = controller
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.NotAllowed(
                reason = "Command creation timestamp is before request timestamp."
            ),
            result
        )
    }

    @Test
    fun directlyProducedTriggerResultCanBeRecordedByHelper() {
        val controller = currentHybridBootstrapCommandTriggerController()
        val producedResult = triggerHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            controller = controller
        )

        val recordedResult = recordExplicitHybridBootstrapTriggerResult(producedResult)

        assertEquals(producedResult, recordedResult)
    }

    @Test
    fun recordingExecutedRejectedTriggerResultPreservesReason() {
        val recordedResult = recordExplicitHybridBootstrapTriggerResult(
            HybridBootstrapCommandTriggerResult.Executed(
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Hybrid bootstrap execution is disabled."
                )
            )
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.Executed(
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Hybrid bootstrap execution is disabled."
                )
            ),
            recordedResult
        )
    }

    @Test
    fun recordingNoCandidatesPreservesNoCandidates() {
        val recordedResult = recordExplicitHybridBootstrapTriggerResult(
            HybridBootstrapCommandTriggerResult.NoCandidates
        )

        assertEquals(HybridBootstrapCommandTriggerResult.NoCandidates, recordedResult)
    }

    @Test
    fun recordingNoSocketReadyCandidatePreservesNoSocketReadyCandidate() {
        val recordedResult = recordExplicitHybridBootstrapTriggerResult(
            HybridBootstrapCommandTriggerResult.NoSocketReadyCandidate
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.NoSocketReadyCandidate,
            recordedResult
        )
    }

    @Test
    fun recordingInvalidEndpointPreservesReason() {
        val recordedResult = recordExplicitHybridBootstrapTriggerResult(
            HybridBootstrapCommandTriggerResult.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            )
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            ),
            recordedResult
        )
    }

    @Test
    fun recordingEndpointTooOldPreservesAgeAndMax() {
        val recordedResult = recordExplicitHybridBootstrapTriggerResult(
            HybridBootstrapCommandTriggerResult.EndpointTooOld(
                ageMillis = 45_000L,
                maxAgeMillis = 30_000L
            )
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.EndpointTooOld(
                ageMillis = 45_000L,
                maxAgeMillis = 30_000L
            ),
            recordedResult
        )
    }

    @Test
    fun recordingNotAllowedPreservesReason() {
        val recordedResult = recordExplicitHybridBootstrapTriggerResult(
            HybridBootstrapCommandTriggerResult.NotAllowed(
                reason = "Command creation timestamp is before request timestamp."
            )
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.NotAllowed(
                reason = "Command creation timestamp is before request timestamp."
            ),
            recordedResult
        )
    }

    @Test
    fun creatingManualTriggerActionDoesNotTriggerControllerOrExecuteExecutor() {
        val executor = RecordingHybridBootstrapCommandExecutor(
            result = HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-manual-create",
                sessionId = "session-manual-create",
                bootstrapIdentifier = "bootstrap-manual-create",
                groupOwnerAddress = "192.168.49.179",
                socketPort = 9179,
                commandCreatedAtMillis = 1_733_000_100L
            )
        )
        val controller = HybridBootstrapCommandTriggerController(executor)
        val recordedResults = mutableListOf<HybridBootstrapCommandTriggerResult>()

        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = {
                builtHybridBootstrapAttemptCommandResult(
                    peerId = "peer-manual-create",
                    sessionId = "session-manual-create",
                    bootstrapIdentifier = "bootstrap-manual-create",
                    groupOwnerAddress = "192.168.49.179",
                    socketPort = 9179,
                    latestCreatedAtMillis = 1_733_000_098L,
                    requestedAtMillis = 1_733_000_099L,
                    commandCreatedAtMillis = 1_733_000_100L
                )
            },
            controllerProvider = { controller },
            recordResult = { recordedResults += it }
        )

        assertNotNull(action)
        assertNull(controller.latestResult)
        assertTrue(controller.triggerHistory.isEmpty())
        assertEquals(0, executor.executeCallCount)
        assertTrue(executor.executedCommands.isEmpty())
        assertTrue(recordedResults.isEmpty())
    }

    @Test
    fun creatingRuntimeStyleManualTriggerActionDoesNotSetLatestTriggerResultOrExecute() {
        val executor = RecordingHybridBootstrapCommandExecutor(
            result = HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-runtime-manual-create",
                sessionId = "session-runtime-manual-create",
                bootstrapIdentifier = "bootstrap-runtime-manual-create",
                groupOwnerAddress = "192.168.49.188",
                socketPort = 9188,
                commandCreatedAtMillis = 1_733_000_190L
            )
        )
        val controller = HybridBootstrapCommandTriggerController(executor)
        var latestBuildResult: HybridBootstrapAttemptCommandBuildResult =
            builtHybridBootstrapAttemptCommandResult(
                peerId = "peer-runtime-manual-create",
                sessionId = "session-runtime-manual-create",
                bootstrapIdentifier = "bootstrap-runtime-manual-create",
                groupOwnerAddress = "192.168.49.188",
                socketPort = 9188,
                latestCreatedAtMillis = 1_733_000_188L,
                requestedAtMillis = 1_733_000_189L,
                commandCreatedAtMillis = 1_733_000_190L
            )
        var latestTriggerResult: HybridBootstrapCommandTriggerResult? =
            initialHybridBootstrapCommandTriggerResult()

        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = { latestBuildResult },
            controllerProvider = { controller },
            recordResult = { latestTriggerResult = it }
        )

        assertNotNull(action)
        assertNull(latestTriggerResult)
        assertNull(controller.latestResult)
        assertTrue(controller.triggerHistory.isEmpty())
        assertEquals(0, executor.executeCallCount)
        assertTrue(executor.executedCommands.isEmpty())
    }

    @Test
    fun creatingManualTriggerRequestCallbackDoesNotInvokeGuardedAction() {
        var invokeCount = 0

        val callback = createHybridBootstrapManualTriggerRequestCallback(
            guardedManualTriggerAction = {
                invokeCount += 1
                HybridBootstrapCommandTriggerResult.NoCandidates
            }
        )

        assertNotNull(callback)
        assertEquals(0, invokeCount)
    }

    @Test
    fun invokingManualTriggerRequestCallbackDelegatesToGuardedActionResult() {
        val expected = HybridBootstrapCommandTriggerResult.NotAllowed(
            reason = "Manual hybrid bootstrap trigger is not available."
        )
        var invokeCount = 0
        val callback = createHybridBootstrapManualTriggerRequestCallback(
            guardedManualTriggerAction = {
                invokeCount += 1
                expected
            }
        )

        val result = callback()

        assertEquals(expected, result)
        assertEquals(1, invokeCount)
    }

    @Test
    fun invokingManualTriggerActionTriggersOnceRecordsOnceAndReturnsRecordedResult() {
        val executor = RecordingHybridBootstrapCommandExecutor(
            result = HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-manual-invoke",
                sessionId = "session-manual-invoke",
                bootstrapIdentifier = "bootstrap-manual-invoke",
                groupOwnerAddress = "192.168.49.180",
                socketPort = 9180,
                commandCreatedAtMillis = 1_733_000_110L
            )
        )
        val controller = HybridBootstrapCommandTriggerController(executor)
        val recordedResults = mutableListOf<HybridBootstrapCommandTriggerResult>()
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = {
                builtHybridBootstrapAttemptCommandResult(
                    peerId = "peer-manual-invoke",
                    sessionId = "session-manual-invoke",
                    bootstrapIdentifier = "bootstrap-manual-invoke",
                    groupOwnerAddress = "192.168.49.180",
                    socketPort = 9180,
                    latestCreatedAtMillis = 1_733_000_108L,
                    requestedAtMillis = 1_733_000_109L,
                    commandCreatedAtMillis = 1_733_000_110L
                )
            },
            controllerProvider = { controller },
            recordResult = { recordedResults += it }
        )

        val result = action()

        val expected = HybridBootstrapCommandTriggerResult.Executed(
            HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-manual-invoke",
                sessionId = "session-manual-invoke",
                bootstrapIdentifier = "bootstrap-manual-invoke",
                groupOwnerAddress = "192.168.49.180",
                socketPort = 9180,
                commandCreatedAtMillis = 1_733_000_110L
            )
        )
        assertEquals(expected, result)
        assertEquals(listOf(expected), recordedResults)
        assertEquals(1, controller.triggerHistory.size)
        assertEquals(expected, controller.latestResult)
        assertEquals(1, executor.executeCallCount)
        assertEquals(1, executor.executedCommands.size)
    }

    @Test
    fun manualTriggerActionUsesLatestBuildResultAtInvocationTime() {
        val executor = RecordingHybridBootstrapCommandExecutor(
            result = HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-manual-latest",
                sessionId = "session-manual-latest",
                bootstrapIdentifier = "bootstrap-manual-latest",
                groupOwnerAddress = "192.168.49.181",
                socketPort = 9181,
                commandCreatedAtMillis = 1_733_000_120L
            )
        )
        val controller = HybridBootstrapCommandTriggerController(executor)
        val recordedResults = mutableListOf<HybridBootstrapCommandTriggerResult>()
        var currentBuildResult: HybridBootstrapAttemptCommandBuildResult =
            HybridBootstrapAttemptCommandBuildResult.NoCandidates
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = { currentBuildResult },
            controllerProvider = { controller },
            recordResult = { recordedResults += it }
        )
        currentBuildResult = builtHybridBootstrapAttemptCommandResult(
            peerId = "peer-manual-latest",
            sessionId = "session-manual-latest",
            bootstrapIdentifier = "bootstrap-manual-latest",
            groupOwnerAddress = "192.168.49.181",
            socketPort = 9181,
            latestCreatedAtMillis = 1_733_000_118L,
            requestedAtMillis = 1_733_000_119L,
            commandCreatedAtMillis = 1_733_000_120L
        )

        val result = action()

        assertTrue(result is HybridBootstrapCommandTriggerResult.Executed)
        assertEquals(1, executor.executeCallCount)
        assertEquals("peer-manual-latest", executor.executedCommands.single().peerId)
        assertEquals(listOf(result), recordedResults)
    }

    @Test
    fun manualTriggerActionUsesLatestControllerAtInvocationTime() {
        val firstExecutor = RecordingHybridBootstrapCommandExecutor(
            result = HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-first-controller",
                sessionId = "session-first-controller",
                bootstrapIdentifier = "bootstrap-first-controller",
                groupOwnerAddress = "192.168.49.182",
                socketPort = 9182,
                commandCreatedAtMillis = 1_733_000_130L
            )
        )
        val secondExecutor = RecordingHybridBootstrapCommandExecutor(
            result = HybridBootstrapCommandExecutionResult.Rejected(
                reason = "second-controller-used"
            )
        )
        val firstController = HybridBootstrapCommandTriggerController(firstExecutor)
        val secondController = HybridBootstrapCommandTriggerController(secondExecutor)
        var currentController = firstController
        val recordedResults = mutableListOf<HybridBootstrapCommandTriggerResult>()
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = {
                builtHybridBootstrapAttemptCommandResult(
                    peerId = "peer-second-controller",
                    sessionId = "session-second-controller",
                    bootstrapIdentifier = "bootstrap-second-controller",
                    groupOwnerAddress = "192.168.49.183",
                    socketPort = 9183,
                    latestCreatedAtMillis = 1_733_000_138L,
                    requestedAtMillis = 1_733_000_139L,
                    commandCreatedAtMillis = 1_733_000_140L
                )
            },
            controllerProvider = { currentController },
            recordResult = { recordedResults += it }
        )
        currentController = secondController

        val result = action()

        assertEquals(
            HybridBootstrapCommandTriggerResult.Executed(
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "second-controller-used"
                )
            ),
            result
        )
        assertNull(firstController.latestResult)
        assertTrue(firstController.triggerHistory.isEmpty())
        assertEquals(0, firstExecutor.executeCallCount)
        assertEquals(1, secondExecutor.executeCallCount)
        assertEquals(result, secondController.latestResult)
        assertEquals(listOf(result), recordedResults)
    }

    @Test
    fun directInvocationOfRuntimeStyleManualTriggerActionUpdatesLatestResultVariable() {
        var latestTriggerResult: HybridBootstrapCommandTriggerResult? = null
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = {
                builtHybridBootstrapAttemptCommandResult(
                    peerId = "peer-runtime-latest-result",
                    sessionId = "session-runtime-latest-result",
                    bootstrapIdentifier = "bootstrap-runtime-latest-result",
                    groupOwnerAddress = "192.168.49.184",
                    socketPort = 9184,
                    latestCreatedAtMillis = 1_733_000_148L,
                    requestedAtMillis = 1_733_000_149L,
                    commandCreatedAtMillis = 1_733_000_150L
                )
            },
            controllerProvider = { currentHybridBootstrapCommandTriggerController() },
            recordResult = { latestTriggerResult = it }
        )

        val result = action()

        assertEquals(result, latestTriggerResult)
    }

    @Test
    fun recordedManualTriggerResultCanRefreshSnapshotWithRejectedStatus() {
        var latestTriggerResult: HybridBootstrapCommandTriggerResult? = null
        var latestSnapshot: HybridBootstrapManualTriggerSnapshot? = null
        val buildResult = builtHybridBootstrapAttemptCommandResult(
            peerId = "peer-runtime-snapshot-rejected",
            sessionId = "session-runtime-snapshot-rejected",
            bootstrapIdentifier = "bootstrap-runtime-snapshot-rejected",
            groupOwnerAddress = "192.168.49.184",
            socketPort = 9184,
            latestCreatedAtMillis = 1_733_000_148L,
            requestedAtMillis = 1_733_000_149L,
            commandCreatedAtMillis = 1_733_000_150L
        )
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = { buildResult },
            controllerProvider = { currentHybridBootstrapCommandTriggerController() },
            recordResult = { result ->
                latestTriggerResult = result
                latestSnapshot = currentHybridBootstrapManualTriggerSnapshot(
                    commandBuildResult = buildResult,
                    latestTriggerResult = result
                )
            }
        )

        val result = action()

        assertEquals(result, latestTriggerResult)
        assertEquals(result, latestSnapshot?.latestTriggerResult)
        assertEquals(
            "Hybrid bootstrap trigger: rejected: Hybrid bootstrap socket connector is disabled.",
            latestSnapshot?.triggerStatusText
        )
    }

    @Test
    fun manualTriggerActionWithRuntimeSocketPlanDisabledControllerReturnsRejectedAndRecordsIt() {
        var latestTriggerResult: HybridBootstrapCommandTriggerResult? = null
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = {
                builtHybridBootstrapAttemptCommandResult(
                    peerId = "peer-no-op-action",
                    sessionId = "session-no-op-action",
                    bootstrapIdentifier = "bootstrap-no-op-action",
                    groupOwnerAddress = "192.168.49.184",
                    socketPort = 9184,
                    latestCreatedAtMillis = 1_733_000_148L,
                    requestedAtMillis = 1_733_000_149L,
                    commandCreatedAtMillis = 1_733_000_150L
                )
            },
            controllerProvider = { currentHybridBootstrapCommandTriggerController() },
            recordResult = { latestTriggerResult = it }
        )

        val result = action()

        assertEquals(
            HybridBootstrapCommandTriggerResult.Executed(
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Hybrid bootstrap socket connector is disabled."
                )
            ),
            result
        )
        assertEquals(result, latestTriggerResult)
    }

    @Test
    fun manualTriggerSnapshotCommandStatusMatchesCommandBuildResultStatus() {
        val buildResult = builtHybridBootstrapAttemptCommandResult(
            peerId = "peer-runtime-snapshot-status",
            sessionId = "session-runtime-snapshot-status",
            bootstrapIdentifier = "bootstrap-runtime-snapshot-status",
            groupOwnerAddress = "192.168.49.193",
            socketPort = 9193,
            latestCreatedAtMillis = 1_733_000_193L,
            requestedAtMillis = 1_733_000_194L,
            commandCreatedAtMillis = 1_733_000_195L
        )

        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = buildResult,
            latestTriggerResult = null
        )

        assertEquals(
            hybridBootstrapAttemptCommandBuildRuntimeStatusText(buildResult),
            snapshot.commandStatusText
        )
    }

    @Test
    fun manualTriggerSnapshotRefreshHelperDoesNotMutateCommandBuildResult() {
        val buildResult = builtHybridBootstrapAttemptCommandResult(
            peerId = "peer-runtime-snapshot-stable",
            sessionId = "session-runtime-snapshot-stable",
            bootstrapIdentifier = "bootstrap-runtime-snapshot-stable",
            groupOwnerAddress = "192.168.49.194",
            socketPort = 9194,
            latestCreatedAtMillis = 1_733_000_196L,
            requestedAtMillis = 1_733_000_197L,
            commandCreatedAtMillis = 1_733_000_198L
        )
        val before = buildResult.copy(
            command = buildResult.command.copy()
        )

        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = buildResult,
            latestTriggerResult = null
        )

        assertEquals(before, buildResult)
        assertNotNull(snapshot)
    }

    @Test
    fun manualTriggerSnapshotRefreshHelperDoesNotMutateTriggerResult() {
        val triggerResult = HybridBootstrapCommandTriggerResult.Executed(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap socket connector is disabled."
            )
        )
        val before = triggerResult.copy(
            executionResult = HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap socket connector is disabled."
            )
        )

        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = builtHybridBootstrapAttemptCommandResult(
                peerId = "peer-runtime-trigger-stable",
                sessionId = "session-runtime-trigger-stable",
                bootstrapIdentifier = "bootstrap-runtime-trigger-stable",
                groupOwnerAddress = "192.168.49.195",
                socketPort = 9195,
                latestCreatedAtMillis = 1_733_000_199L,
                requestedAtMillis = 1_733_000_200L,
                commandCreatedAtMillis = 1_733_000_201L
            ),
            latestTriggerResult = triggerResult
        )

        assertEquals(before, triggerResult)
        assertNotNull(snapshot)
    }

    @Test
    fun guardedManualTriggerHelperInvokesManualTriggerActionWhenSnapshotCanTriggerNowTrue() {
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = builtHybridBootstrapAttemptCommandResult(
                peerId = "peer-guard-available",
                sessionId = "session-guard-available",
                bootstrapIdentifier = "bootstrap-guard-available",
                groupOwnerAddress = "192.168.49.196",
                socketPort = 9196,
                latestCreatedAtMillis = 1_733_000_202L,
                requestedAtMillis = 1_733_000_203L,
                commandCreatedAtMillis = 1_733_000_204L
            ),
            latestTriggerResult = null
        )
        var invokeCount = 0

        triggerHybridBootstrapManuallyIfAvailable(
            snapshot = snapshot,
            manualTriggerAction = {
                invokeCount += 1
                HybridBootstrapCommandTriggerResult.NoCandidates
            }
        )

        assertEquals(1, invokeCount)
    }

    @Test
    fun guardedManualTriggerHelperReturnsActionResultWhenAvailable() {
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = builtHybridBootstrapAttemptCommandResult(
                peerId = "peer-guard-result",
                sessionId = "session-guard-result",
                bootstrapIdentifier = "bootstrap-guard-result",
                groupOwnerAddress = "192.168.49.197",
                socketPort = 9197,
                latestCreatedAtMillis = 1_733_000_205L,
                requestedAtMillis = 1_733_000_206L,
                commandCreatedAtMillis = 1_733_000_207L
            ),
            latestTriggerResult = null
        )
        val expected = HybridBootstrapCommandTriggerResult.Executed(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap socket connector is disabled."
            )
        )

        val result = triggerHybridBootstrapManuallyIfAvailable(
            snapshot = snapshot,
            manualTriggerAction = { expected }
        )

        assertEquals(expected, result)
    }

    @Test
    fun guardedManualTriggerHelperDoesNotInvokeManualTriggerActionWhenSnapshotCanTriggerNowFalse() {
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = null
        )
        var invokeCount = 0

        val result = triggerHybridBootstrapManuallyIfAvailable(
            snapshot = snapshot,
            manualTriggerAction = {
                invokeCount += 1
                HybridBootstrapCommandTriggerResult.NoCandidates
            }
        )

        assertEquals(0, invokeCount)
        assertEquals(
            HybridBootstrapCommandTriggerResult.NotAllowed(
                reason = "Manual hybrid bootstrap trigger is not available."
            ),
            result
        )
    }

    @Test
    fun guardedManualTriggerHelperReturnsNotAllowedWhenSnapshotUnavailable() {
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = null
        )

        val result = triggerHybridBootstrapManuallyIfAvailable(
            snapshot = snapshot,
            manualTriggerAction = { HybridBootstrapCommandTriggerResult.NoCandidates }
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.NotAllowed(
                reason = "Manual hybrid bootstrap trigger is not available."
            ),
            result
        )
    }

    @Test
    fun guardedManualTriggerHelperDoesNotMutateSnapshot() {
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = builtHybridBootstrapAttemptCommandResult(
                peerId = "peer-guard-stable",
                sessionId = "session-guard-stable",
                bootstrapIdentifier = "bootstrap-guard-stable",
                groupOwnerAddress = "192.168.49.198",
                socketPort = 9198,
                latestCreatedAtMillis = 1_733_000_208L,
                requestedAtMillis = 1_733_000_209L,
                commandCreatedAtMillis = 1_733_000_210L
            ),
            latestTriggerResult = HybridBootstrapCommandTriggerResult.Executed(
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Hybrid bootstrap socket connector is disabled."
                )
            )
        )
        val before = snapshot.copy(
            commandBuildResult = builtHybridBootstrapAttemptCommandResult(
                peerId = "peer-guard-stable",
                sessionId = "session-guard-stable",
                bootstrapIdentifier = "bootstrap-guard-stable",
                groupOwnerAddress = "192.168.49.198",
                socketPort = 9198,
                latestCreatedAtMillis = 1_733_000_208L,
                requestedAtMillis = 1_733_000_209L,
                commandCreatedAtMillis = 1_733_000_210L
            ),
            latestTriggerResult = HybridBootstrapCommandTriggerResult.Executed(
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Hybrid bootstrap socket connector is disabled."
                )
            )
        )

        val result = triggerHybridBootstrapManuallyIfAvailable(
            snapshot = snapshot,
            manualTriggerAction = { HybridBootstrapCommandTriggerResult.NoCandidates }
        )

        assertEquals(before, snapshot)
        assertEquals(HybridBootstrapCommandTriggerResult.NoCandidates, result)
    }

    @Test
    fun builtSnapshotWithRuntimeNoOpActionReturnsRejected() {
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = builtHybridBootstrapAttemptCommandResult(
                peerId = "peer-guard-no-op",
                sessionId = "session-guard-no-op",
                bootstrapIdentifier = "bootstrap-guard-no-op",
                groupOwnerAddress = "192.168.49.199",
                socketPort = 9199,
                latestCreatedAtMillis = 1_733_000_211L,
                requestedAtMillis = 1_733_000_212L,
                commandCreatedAtMillis = 1_733_000_213L
            ),
            latestTriggerResult = null
        )
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = { snapshot.commandBuildResult },
            controllerProvider = { currentHybridBootstrapCommandTriggerController() },
            recordResult = {}
        )

        val result = triggerHybridBootstrapManuallyIfAvailable(
            snapshot = snapshot,
            manualTriggerAction = action
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.Executed(
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Hybrid bootstrap socket connector is disabled."
                )
            ),
            result
        )
    }

    @Test
    fun noCandidatesSnapshotReturnsNotAllowedWithoutInvokingAction() {
        assertUnavailableManualTriggerSnapshotDoesNotInvokeAction(
            snapshot = currentHybridBootstrapManualTriggerSnapshot(
                commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
                latestTriggerResult = null
            )
        )
    }

    @Test
    fun noSocketReadyCandidateSnapshotReturnsNotAllowedWithoutInvokingAction() {
        assertUnavailableManualTriggerSnapshotDoesNotInvokeAction(
            snapshot = currentHybridBootstrapManualTriggerSnapshot(
                commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate,
                latestTriggerResult = null
            )
        )
    }

    @Test
    fun invalidEndpointSnapshotReturnsNotAllowedWithoutInvokingAction() {
        assertUnavailableManualTriggerSnapshotDoesNotInvokeAction(
            snapshot = currentHybridBootstrapManualTriggerSnapshot(
                commandBuildResult = HybridBootstrapAttemptCommandBuildResult.InvalidEndpoint(
                    reason = "Endpoint timestamp is in the future."
                ),
                latestTriggerResult = null
            )
        )
    }

    @Test
    fun endpointTooOldSnapshotReturnsNotAllowedWithoutInvokingAction() {
        assertUnavailableManualTriggerSnapshotDoesNotInvokeAction(
            snapshot = currentHybridBootstrapManualTriggerSnapshot(
                commandBuildResult = HybridBootstrapAttemptCommandBuildResult.EndpointTooOld(
                    ageMillis = 45_000L,
                    maxAgeMillis = 30_000L
                ),
                latestTriggerResult = null
            )
        )
    }

    @Test
    fun notAllowedCommandBuildSnapshotReturnsNotAllowedWithoutInvokingAction() {
        assertUnavailableManualTriggerSnapshotDoesNotInvokeAction(
            snapshot = currentHybridBootstrapManualTriggerSnapshot(
                commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NotAllowed(
                    reason = "Command creation timestamp is before request timestamp."
                ),
                latestTriggerResult = null
            )
        )
    }

    @Test
    fun guardedManualTriggerHelperDoesNotAppendGlobalChatMessages() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val initialGlobalMessages = holder.uiState.globalMessages
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = null
        )

        triggerHybridBootstrapManuallyIfAvailable(
            snapshot = snapshot,
            manualTriggerAction = { HybridBootstrapCommandTriggerResult.NoCandidates }
        )

        assertEquals(initialGlobalMessages, holder.uiState.globalMessages)
    }

    @Test
    fun guardedManualTriggerHelperDoesNotAppendPrivateChatMessages() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val initialPrivateMessages = holder.uiState.privateMessagesByPeerId
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = null
        )

        triggerHybridBootstrapManuallyIfAvailable(
            snapshot = snapshot,
            manualTriggerAction = { HybridBootstrapCommandTriggerResult.NoCandidates }
        )

        assertEquals(initialPrivateMessages, holder.uiState.privateMessagesByPeerId)
    }

    @Test
    fun creatingRuntimeStyleGuardedActionDoesNotInvokeUnderlyingActionOrExecute() {
        val executor = RecordingHybridBootstrapCommandExecutor(
            result = HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-guarded-create",
                sessionId = "session-guarded-create",
                bootstrapIdentifier = "bootstrap-guarded-create",
                groupOwnerAddress = "192.168.49.200",
                socketPort = 9200,
                commandCreatedAtMillis = 1_733_000_214L
            )
        )
        val controller = HybridBootstrapCommandTriggerController(executor)
        var latestTriggerResult: HybridBootstrapCommandTriggerResult? =
            initialHybridBootstrapCommandTriggerResult()
        var latestSnapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = latestTriggerResult
        )
        val manualAction = createHybridBootstrapManualTriggerAction(
            buildResultProvider = { latestSnapshot.commandBuildResult },
            controllerProvider = { controller },
            recordResult = { result ->
                latestTriggerResult = result
                latestSnapshot = currentHybridBootstrapManualTriggerSnapshot(
                    commandBuildResult = latestSnapshot.commandBuildResult,
                    latestTriggerResult = result
                )
            }
        )

        val guardedAction = {
            triggerHybridBootstrapManuallyIfAvailable(
                snapshot = latestSnapshot,
                manualTriggerAction = manualAction
            )
        }

        assertNotNull(guardedAction)
        assertNull(latestTriggerResult)
        assertNull(controller.latestResult)
        assertTrue(controller.triggerHistory.isEmpty())
        assertEquals(0, executor.executeCallCount)
        assertTrue(executor.executedCommands.isEmpty())
    }

    @Test
    fun directInvocationOfRuntimeStyleGuardedActionWithUnavailableSnapshotReturnsNotAllowed() {
        var latestSnapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = null
        )
        var underlyingInvokeCount = 0
        val guardedAction = {
            triggerHybridBootstrapManuallyIfAvailable(
                snapshot = latestSnapshot,
                manualTriggerAction = {
                    underlyingInvokeCount += 1
                    HybridBootstrapCommandTriggerResult.NoCandidates
                }
            )
        }

        val result = guardedAction()

        assertEquals(
            HybridBootstrapCommandTriggerResult.NotAllowed(
                reason = "Manual hybrid bootstrap trigger is not available."
            ),
            result
        )
        assertEquals(0, underlyingInvokeCount)
    }

    @Test
    fun directInvocationOfRuntimeStyleGuardedActionWithAvailableBuiltSnapshotInvokesUnderlyingAction() {
        var latestTriggerResult: HybridBootstrapCommandTriggerResult? = null
        var latestSnapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = builtHybridBootstrapAttemptCommandResult(
                peerId = "peer-guarded-runtime",
                sessionId = "session-guarded-runtime",
                bootstrapIdentifier = "bootstrap-guarded-runtime",
                groupOwnerAddress = "192.168.49.201",
                socketPort = 9201,
                latestCreatedAtMillis = 1_733_000_215L,
                requestedAtMillis = 1_733_000_216L,
                commandCreatedAtMillis = 1_733_000_217L
            ),
            latestTriggerResult = latestTriggerResult
        )
        val manualAction = createHybridBootstrapManualTriggerAction(
            buildResultProvider = { latestSnapshot.commandBuildResult },
            controllerProvider = { currentHybridBootstrapCommandTriggerController() },
            recordResult = { result ->
                latestTriggerResult = result
                latestSnapshot = currentHybridBootstrapManualTriggerSnapshot(
                    commandBuildResult = latestSnapshot.commandBuildResult,
                    latestTriggerResult = result
                )
            }
        )
        val guardedAction = {
            triggerHybridBootstrapManuallyIfAvailable(
                snapshot = latestSnapshot,
                manualTriggerAction = manualAction
            )
        }

        val result = guardedAction()

        assertEquals(
            HybridBootstrapCommandTriggerResult.Executed(
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Hybrid bootstrap socket connector is disabled."
                )
            ),
            result
        )
        assertEquals(result, latestTriggerResult)
        assertEquals(result, latestSnapshot.latestTriggerResult)
    }

    @Test
    fun runtimeStyleGuardedActionUsesLatestSnapshotAtInvocationTime() {
        var latestTriggerResult: HybridBootstrapCommandTriggerResult? = null
        var latestSnapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = latestTriggerResult
        )
        var underlyingInvokeCount = 0
        val manualAction = {
            underlyingInvokeCount += 1
            HybridBootstrapCommandTriggerResult.NoCandidates
        }
        val guardedAction = {
            triggerHybridBootstrapManuallyIfAvailable(
                snapshot = latestSnapshot,
                manualTriggerAction = manualAction
            )
        }
        latestSnapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = builtHybridBootstrapAttemptCommandResult(
                peerId = "peer-guarded-latest",
                sessionId = "session-guarded-latest",
                bootstrapIdentifier = "bootstrap-guarded-latest",
                groupOwnerAddress = "192.168.49.202",
                socketPort = 9202,
                latestCreatedAtMillis = 1_733_000_218L,
                requestedAtMillis = 1_733_000_219L,
                commandCreatedAtMillis = 1_733_000_220L
            ),
            latestTriggerResult = latestTriggerResult
        )

        val result = guardedAction()

        assertEquals(HybridBootstrapCommandTriggerResult.NoCandidates, result)
        assertEquals(1, underlyingInvokeCount)
        assertTrue(latestSnapshot.canTriggerNow)
    }

    @Test
    fun runtimeStyleManualTriggerRequestCallbackWithUnavailableSnapshotReturnsNotAllowedWithoutExecuting() {
        var latestSnapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = null
        )
        var underlyingInvokeCount = 0
        val guardedAction = {
            triggerHybridBootstrapManuallyIfAvailable(
                snapshot = latestSnapshot,
                manualTriggerAction = {
                    underlyingInvokeCount += 1
                    HybridBootstrapCommandTriggerResult.NoCandidates
                }
            )
        }
        val callback = createHybridBootstrapManualTriggerRequestCallback(
            guardedManualTriggerAction = guardedAction
        )

        val result = callback()

        assertEquals(
            HybridBootstrapCommandTriggerResult.NotAllowed(
                reason = "Manual hybrid bootstrap trigger is not available."
            ),
            result
        )
        assertEquals(0, underlyingInvokeCount)
    }

    @Test
    fun runtimeStyleManualTriggerRequestCallbackWithAvailableBuiltSnapshotReturnsExecutedRejectedAndRefreshesSnapshot() {
        var latestTriggerResult: HybridBootstrapCommandTriggerResult? = null
        var latestSnapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = builtHybridBootstrapAttemptCommandResult(
                peerId = "peer-runtime-callback",
                sessionId = "session-runtime-callback",
                bootstrapIdentifier = "bootstrap-runtime-callback",
                groupOwnerAddress = "192.168.49.204",
                socketPort = 9204,
                latestCreatedAtMillis = 1_733_000_224L,
                requestedAtMillis = 1_733_000_225L,
                commandCreatedAtMillis = 1_733_000_226L
            ),
            latestTriggerResult = latestTriggerResult
        )
        val manualAction = createHybridBootstrapManualTriggerAction(
            buildResultProvider = { latestSnapshot.commandBuildResult },
            controllerProvider = { currentHybridBootstrapCommandTriggerController() },
            recordResult = { result ->
                latestTriggerResult = result
                latestSnapshot = currentHybridBootstrapManualTriggerSnapshot(
                    commandBuildResult = latestSnapshot.commandBuildResult,
                    latestTriggerResult = result
                )
            }
        )
        val guardedAction = {
            triggerHybridBootstrapManuallyIfAvailable(
                snapshot = latestSnapshot,
                manualTriggerAction = manualAction
            )
        }
        val callback = createHybridBootstrapManualTriggerRequestCallback(
            guardedManualTriggerAction = guardedAction
        )

        val result = callback()

        assertEquals(
            HybridBootstrapCommandTriggerResult.Executed(
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Hybrid bootstrap socket connector is disabled."
                )
            ),
            result
        )
        assertEquals(result, latestTriggerResult)
        assertEquals(
            "Hybrid bootstrap trigger: rejected: Hybrid bootstrap socket connector is disabled.",
            latestSnapshot.triggerStatusText
        )
    }

    @Test
    fun runtimeStyleManualTriggerRequestCallbackUsesLatestSnapshotAtInvocationTime() {
        var latestTriggerResult: HybridBootstrapCommandTriggerResult? = null
        var latestSnapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = latestTriggerResult
        )
        var underlyingInvokeCount = 0
        val guardedAction = {
            triggerHybridBootstrapManuallyIfAvailable(
                snapshot = latestSnapshot,
                manualTriggerAction = {
                    underlyingInvokeCount += 1
                    HybridBootstrapCommandTriggerResult.NoCandidates
                }
            )
        }
        val callback = createHybridBootstrapManualTriggerRequestCallback(
            guardedManualTriggerAction = guardedAction
        )
        latestSnapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = builtHybridBootstrapAttemptCommandResult(
                peerId = "peer-runtime-callback-latest",
                sessionId = "session-runtime-callback-latest",
                bootstrapIdentifier = "bootstrap-runtime-callback-latest",
                groupOwnerAddress = "192.168.49.205",
                socketPort = 9205,
                latestCreatedAtMillis = 1_733_000_227L,
                requestedAtMillis = 1_733_000_228L,
                commandCreatedAtMillis = 1_733_000_229L
            ),
            latestTriggerResult = latestTriggerResult
        )

        val result = callback()

        assertEquals(HybridBootstrapCommandTriggerResult.NoCandidates, result)
        assertEquals(1, underlyingInvokeCount)
        assertTrue(latestSnapshot.canTriggerNow)
    }

    @Test
    fun runtimeStyleGuardedActionDoesNotMutateSnapshotOrCommandBuildResult() {
        var latestTriggerResult: HybridBootstrapCommandTriggerResult? = null
        val buildResult = builtHybridBootstrapAttemptCommandResult(
            peerId = "peer-guarded-stable",
            sessionId = "session-guarded-stable",
            bootstrapIdentifier = "bootstrap-guarded-stable",
            groupOwnerAddress = "192.168.49.203",
            socketPort = 9203,
            latestCreatedAtMillis = 1_733_000_221L,
            requestedAtMillis = 1_733_000_222L,
            commandCreatedAtMillis = 1_733_000_223L
        )
        val buildBefore = buildResult.copy(command = buildResult.command.copy())
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = buildResult,
            latestTriggerResult = latestTriggerResult
        )
        val snapshotBefore = snapshot.copy(
            commandBuildResult = buildResult.copy(command = buildResult.command.copy()),
            latestTriggerResult = null
        )
        val guardedAction = {
            triggerHybridBootstrapManuallyIfAvailable(
                snapshot = snapshot,
                manualTriggerAction = { HybridBootstrapCommandTriggerResult.NoCandidates }
            )
        }

        val result = guardedAction()

        assertEquals(HybridBootstrapCommandTriggerResult.NoCandidates, result)
        assertEquals(buildBefore, buildResult)
        assertEquals(snapshotBefore, snapshot)
    }

    @Test
    fun runtimeStyleGuardedActionDoesNotAppendGlobalChatMessages() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val initialGlobalMessages = holder.uiState.globalMessages
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = null
        )
        val guardedAction = {
            triggerHybridBootstrapManuallyIfAvailable(
                snapshot = snapshot,
                manualTriggerAction = { HybridBootstrapCommandTriggerResult.NoCandidates }
            )
        }

        guardedAction()

        assertEquals(initialGlobalMessages, holder.uiState.globalMessages)
    }

    @Test
    fun runtimeStyleGuardedActionDoesNotAppendPrivateChatMessages() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val initialPrivateMessages = holder.uiState.privateMessagesByPeerId
        val snapshot = currentHybridBootstrapManualTriggerSnapshot(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = null
        )
        val guardedAction = {
            triggerHybridBootstrapManuallyIfAvailable(
                snapshot = snapshot,
                manualTriggerAction = { HybridBootstrapCommandTriggerResult.NoCandidates }
            )
        }

        guardedAction()

        assertEquals(initialPrivateMessages, holder.uiState.privateMessagesByPeerId)
    }

    @Test
    fun manualTriggerActionWithNoCandidatesReturnsAndRecordsNoCandidates() {
        val recordedResults = mutableListOf<HybridBootstrapCommandTriggerResult>()
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = { HybridBootstrapAttemptCommandBuildResult.NoCandidates },
            controllerProvider = { currentHybridBootstrapCommandTriggerController() },
            recordResult = { recordedResults += it }
        )

        val result = action()

        assertEquals(HybridBootstrapCommandTriggerResult.NoCandidates, result)
        assertEquals(listOf(result), recordedResults)
    }

    @Test
    fun manualTriggerActionWithNoSocketReadyCandidateReturnsAndRecordsNoSocketReadyCandidate() {
        val recordedResults = mutableListOf<HybridBootstrapCommandTriggerResult>()
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = { HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate },
            controllerProvider = { currentHybridBootstrapCommandTriggerController() },
            recordResult = { recordedResults += it }
        )

        val result = action()

        assertEquals(HybridBootstrapCommandTriggerResult.NoSocketReadyCandidate, result)
        assertEquals(listOf(result), recordedResults)
    }

    @Test
    fun manualTriggerActionWithInvalidEndpointPreservesAndRecordsReason() {
        val recordedResults = mutableListOf<HybridBootstrapCommandTriggerResult>()
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = {
                HybridBootstrapAttemptCommandBuildResult.InvalidEndpoint(
                    reason = "Endpoint timestamp is in the future."
                )
            },
            controllerProvider = { currentHybridBootstrapCommandTriggerController() },
            recordResult = { recordedResults += it }
        )

        val result = action()

        assertEquals(
            HybridBootstrapCommandTriggerResult.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            ),
            result
        )
        assertEquals(listOf(result), recordedResults)
    }

    @Test
    fun manualTriggerActionWithEndpointTooOldPreservesAndRecordsAgeAndMax() {
        val recordedResults = mutableListOf<HybridBootstrapCommandTriggerResult>()
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = {
                HybridBootstrapAttemptCommandBuildResult.EndpointTooOld(
                    ageMillis = 45_000L,
                    maxAgeMillis = 30_000L
                )
            },
            controllerProvider = { currentHybridBootstrapCommandTriggerController() },
            recordResult = { recordedResults += it }
        )

        val result = action()

        assertEquals(
            HybridBootstrapCommandTriggerResult.EndpointTooOld(
                ageMillis = 45_000L,
                maxAgeMillis = 30_000L
            ),
            result
        )
        assertEquals(listOf(result), recordedResults)
    }

    @Test
    fun manualTriggerActionWithNotAllowedPreservesAndRecordsReason() {
        val recordedResults = mutableListOf<HybridBootstrapCommandTriggerResult>()
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = {
                HybridBootstrapAttemptCommandBuildResult.NotAllowed(
                    reason = "Command creation timestamp is before request timestamp."
                )
            },
            controllerProvider = { currentHybridBootstrapCommandTriggerController() },
            recordResult = { recordedResults += it }
        )

        val result = action()

        assertEquals(
            HybridBootstrapCommandTriggerResult.NotAllowed(
                reason = "Command creation timestamp is before request timestamp."
            ),
            result
        )
        assertEquals(listOf(result), recordedResults)
    }

    @Test
    fun triggerAndRecordHelperTriggersRecordsAndReturnsExecuted() {
        val controller = currentHybridBootstrapCommandTriggerController()
        val recordedResults = mutableListOf<HybridBootstrapCommandTriggerResult>()
        val buildResult = HybridBootstrapAttemptCommandBuildResult.Built(
            HybridBootstrapAttemptCommand(
                peerId = "peer-trigger-record",
                sessionId = "session-trigger-record",
                bootstrapIdentifier = "bootstrap-trigger-record",
                groupOwnerAddress = "192.168.49.179",
                socketPort = 9179,
                latestCreatedAtMillis = 1_733_000_100L,
                requestedAtMillis = 1_733_000_101L,
                commandCreatedAtMillis = 1_733_000_102L
            )
        )

        val result = triggerAndRecordHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = buildResult,
            controller = controller,
            recordResult = { recordedResults += it }
        )

        val expected = HybridBootstrapCommandTriggerResult.Executed(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap socket connector is disabled."
            )
        )
        assertEquals(expected, result)
        assertEquals(listOf(recordExplicitHybridBootstrapTriggerResult(expected)), recordedResults)
        assertEquals(expected, controller.latestResult)
        assertEquals(listOf(expected), controller.triggerHistory)
    }

    @Test
    fun triggerAndRecordHelperInvokesRecorderExactlyOnce() {
        val controller = currentHybridBootstrapCommandTriggerController()
        val recordedResults = mutableListOf<HybridBootstrapCommandTriggerResult>()

        val result = triggerAndRecordHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            controller = controller,
            recordResult = { recordedResults += it }
        )

        assertEquals(HybridBootstrapCommandTriggerResult.NoCandidates, result)
        assertEquals(
            listOf(recordExplicitHybridBootstrapTriggerResult(result)),
            recordedResults
        )
        assertEquals(1, recordedResults.size)
    }

    @Test
    fun triggerAndRecordHelperDoesNotMutateBuildResult() {
        val controller = currentHybridBootstrapCommandTriggerController()
        val buildResult = HybridBootstrapAttemptCommandBuildResult.Built(
            HybridBootstrapAttemptCommand(
                peerId = "peer-trigger-record-stable",
                sessionId = "session-trigger-record-stable",
                bootstrapIdentifier = "bootstrap-trigger-record-stable",
                groupOwnerAddress = "192.168.49.180",
                socketPort = 9180,
                latestCreatedAtMillis = 1_733_000_110L,
                requestedAtMillis = 1_733_000_111L,
                commandCreatedAtMillis = 1_733_000_112L
            )
        )
        val before = buildResult.copy(
            command = buildResult.command.copy()
        )
        val recordedResults = mutableListOf<HybridBootstrapCommandTriggerResult>()

        val result = triggerAndRecordHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = buildResult,
            controller = controller,
            recordResult = { recordedResults += it }
        )

        assertTrue(result is HybridBootstrapCommandTriggerResult.Executed)
        assertEquals(before, buildResult)
        assertEquals(1, recordedResults.size)
    }

    @Test
    fun manualTriggerActionDoesNotMutateBuildResult() {
        var buildResult = builtHybridBootstrapAttemptCommandResult(
            peerId = "peer-manual-stable",
            sessionId = "session-manual-stable",
            bootstrapIdentifier = "bootstrap-manual-stable",
            groupOwnerAddress = "192.168.49.185",
            socketPort = 9185,
            latestCreatedAtMillis = 1_733_000_158L,
            requestedAtMillis = 1_733_000_159L,
            commandCreatedAtMillis = 1_733_000_160L
        )
        val before = buildResult.copy(
            command = buildResult.command.copy()
        )
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = { buildResult },
            controllerProvider = { currentHybridBootstrapCommandTriggerController() },
            recordResult = {}
        )

        val result = action()

        assertTrue(result is HybridBootstrapCommandTriggerResult.Executed)
        assertEquals(before, buildResult)
    }

    @Test
    fun manualTriggerActionDoesNotAppendGlobalChatMessages() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val initialGlobalMessages = holder.uiState.globalMessages
        var latestTriggerResult: HybridBootstrapCommandTriggerResult? = null
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = {
                builtHybridBootstrapAttemptCommandResult(
                    peerId = "peer-manual-global",
                    sessionId = "session-manual-global",
                    bootstrapIdentifier = "bootstrap-manual-global",
                    groupOwnerAddress = "192.168.49.186",
                    socketPort = 9186,
                    latestCreatedAtMillis = 1_733_000_168L,
                    requestedAtMillis = 1_733_000_169L,
                    commandCreatedAtMillis = 1_733_000_170L
                )
            },
            controllerProvider = { currentHybridBootstrapCommandTriggerController() },
            recordResult = { latestTriggerResult = it }
        )

        action()

        assertNotNull(latestTriggerResult)
        assertEquals(initialGlobalMessages, holder.uiState.globalMessages)
    }

    @Test
    fun manualTriggerActionDoesNotAppendPrivateChatMessages() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val initialPrivateMessages = holder.uiState.privateMessagesByPeerId
        var latestTriggerResult: HybridBootstrapCommandTriggerResult? = null
        val action = createHybridBootstrapManualTriggerAction(
            buildResultProvider = {
                builtHybridBootstrapAttemptCommandResult(
                    peerId = "peer-manual-private",
                    sessionId = "session-manual-private",
                    bootstrapIdentifier = "bootstrap-manual-private",
                    groupOwnerAddress = "192.168.49.187",
                    socketPort = 9187,
                    latestCreatedAtMillis = 1_733_000_178L,
                    requestedAtMillis = 1_733_000_179L,
                    commandCreatedAtMillis = 1_733_000_180L
                )
            },
            controllerProvider = { currentHybridBootstrapCommandTriggerController() },
            recordResult = { latestTriggerResult = it }
        )

        action()

        assertNotNull(latestTriggerResult)
        assertEquals(initialPrivateMessages, holder.uiState.privateMessagesByPeerId)
    }

    @Test
    fun triggerAndRecordHelperDoesNotAppendGlobalChatMessages() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val initialGlobalMessages = holder.uiState.globalMessages
        var latestTriggerResult: HybridBootstrapCommandTriggerResult? = null

        triggerAndRecordHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.Built(
                HybridBootstrapAttemptCommand(
                    peerId = "peer-trigger-record-global",
                    sessionId = "session-trigger-record-global",
                    bootstrapIdentifier = "bootstrap-trigger-record-global",
                    groupOwnerAddress = "192.168.49.181",
                    socketPort = 9181,
                    latestCreatedAtMillis = 1_733_000_120L,
                    requestedAtMillis = 1_733_000_121L,
                    commandCreatedAtMillis = 1_733_000_122L
                )
            ),
            controller = currentHybridBootstrapCommandTriggerController(),
            recordResult = { latestTriggerResult = it }
        )

        assertNotNull(latestTriggerResult)
        assertEquals(initialGlobalMessages, holder.uiState.globalMessages)
    }

    @Test
    fun triggerAndRecordHelperDoesNotAppendPrivateChatMessages() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val initialPrivateMessages = holder.uiState.privateMessagesByPeerId
        var latestTriggerResult: HybridBootstrapCommandTriggerResult? = null

        triggerAndRecordHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.Built(
                HybridBootstrapAttemptCommand(
                    peerId = "peer-trigger-record-private",
                    sessionId = "session-trigger-record-private",
                    bootstrapIdentifier = "bootstrap-trigger-record-private",
                    groupOwnerAddress = "192.168.49.182",
                    socketPort = 9182,
                    latestCreatedAtMillis = 1_733_000_130L,
                    requestedAtMillis = 1_733_000_131L,
                    commandCreatedAtMillis = 1_733_000_132L
                )
            ),
            controller = currentHybridBootstrapCommandTriggerController(),
            recordResult = { latestTriggerResult = it }
        )

        assertNotNull(latestTriggerResult)
        assertEquals(initialPrivateMessages, holder.uiState.privateMessagesByPeerId)
    }

    @Test
    fun explicitHelperDoesNotMutateBuildResult() {
        val controller = currentHybridBootstrapCommandTriggerController()
        val buildResult = HybridBootstrapAttemptCommandBuildResult.Built(
            HybridBootstrapAttemptCommand(
                peerId = "peer-explicit-stable",
                sessionId = "session-explicit-stable",
                bootstrapIdentifier = "bootstrap-explicit-stable",
                groupOwnerAddress = "192.168.49.176",
                socketPort = 9176,
                latestCreatedAtMillis = 1_733_000_070L,
                requestedAtMillis = 1_733_000_071L,
                commandCreatedAtMillis = 1_733_000_072L
            )
        )
        val before = buildResult.copy(
            command = buildResult.command.copy()
        )

        val result = triggerHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = buildResult,
            controller = controller
        )

        assertTrue(result is HybridBootstrapCommandTriggerResult.Executed)
        assertEquals(before, buildResult)
    }

    @Test
    fun explicitHelperDoesNotAppendGlobalChatMessages() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val initialGlobalMessages = holder.uiState.globalMessages

        triggerHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.Built(
                HybridBootstrapAttemptCommand(
                    peerId = "peer-explicit-global",
                    sessionId = "session-explicit-global",
                    bootstrapIdentifier = "bootstrap-explicit-global",
                    groupOwnerAddress = "192.168.49.177",
                    socketPort = 9177,
                    latestCreatedAtMillis = 1_733_000_080L,
                    requestedAtMillis = 1_733_000_081L,
                    commandCreatedAtMillis = 1_733_000_082L
                )
            ),
            controller = currentHybridBootstrapCommandTriggerController()
        )

        assertEquals(initialGlobalMessages, holder.uiState.globalMessages)
    }

    @Test
    fun explicitHelperDoesNotAppendPrivateChatMessages() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val initialPrivateMessages = holder.uiState.privateMessagesByPeerId

        triggerHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = HybridBootstrapAttemptCommandBuildResult.Built(
                HybridBootstrapAttemptCommand(
                    peerId = "peer-explicit-private",
                    sessionId = "session-explicit-private",
                    bootstrapIdentifier = "bootstrap-explicit-private",
                    groupOwnerAddress = "192.168.49.178",
                    socketPort = 9178,
                    latestCreatedAtMillis = 1_733_000_090L,
                    requestedAtMillis = 1_733_000_091L,
                    commandCreatedAtMillis = 1_733_000_092L
                )
            ),
            controller = currentHybridBootstrapCommandTriggerController()
        )

        assertEquals(initialPrivateMessages, holder.uiState.privateMessagesByPeerId)
    }

    @Test
    fun nullSelectedFieldsInNonSelectedDiagnosticsDoNotCrash() {
        val diagnostics = HybridBootstrapDiagnostics(
            candidateCount = 1,
            socketReadyCandidateCount = 0,
            selectionStatus = HybridBootstrapDiagnostics.SelectionStatus.NoSocketReadyCandidates,
            selectedPeerId = null,
            selectedSessionId = null,
            selectedGroupOwnerAddress = null,
            selectedSocketPort = null,
            selectedLatestCreatedAtMillis = null,
            statusText = "Hybrid bootstrap candidates available, none socket-ready"
        )

        assertEquals(
            "Hybrid bootstrap: candidates available, none socket-ready",
            hybridBootstrapDiagnosticsRuntimeStatusText(diagnostics)
        )
    }

    @Test
    fun invalidSelectedCandidateEndpointResolutionMapsToStableRuntimeStatusText() {
        val resolution = HybridBootstrapSocketEndpointResolution.InvalidSelectedCandidate(
            reason = "Selected hybrid bootstrap candidate socketPort is missing."
        )

        assertEquals(
            "Hybrid bootstrap endpoint: invalid selected candidate: Selected hybrid bootstrap candidate socketPort is missing.",
            hybridBootstrapSocketEndpointRuntimeStatusText(resolution)
        )
    }

    @Test
    fun invalidEndpointAttemptDecisionMapsToStableRuntimeStatusText() {
        val decision = HybridBootstrapAttemptDecision.InvalidEndpoint(
            reason = "Endpoint timestamp is in the future."
        )

        assertEquals(
            "Hybrid bootstrap attempt: invalid endpoint: Endpoint timestamp is in the future.",
            hybridBootstrapAttemptRuntimeStatusText(decision)
        )
    }

    @Test
    fun endpointTooOldAttemptDecisionMapsToStableRuntimeStatusText() {
        val decision = HybridBootstrapAttemptDecision.EndpointTooOld(
            ageMillis = 45_000L,
            maxAgeMillis = 30_000L
        )

        assertEquals(
            "Hybrid bootstrap attempt: endpoint too old age=45000 max=30000",
            hybridBootstrapAttemptRuntimeStatusText(decision)
        )
    }

    @Test
    fun invalidEndpointCommandBuildStatusTextMapsToStableRuntimeStatusText() {
        val result = HybridBootstrapAttemptCommandBuildResult.InvalidEndpoint(
            reason = "Endpoint timestamp is in the future."
        )

        assertEquals(
            "Hybrid bootstrap command: invalid endpoint: Endpoint timestamp is in the future.",
            hybridBootstrapAttemptCommandBuildRuntimeStatusText(result)
        )
    }

    @Test
    fun endpointTooOldCommandBuildStatusTextMapsToStableRuntimeStatusText() {
        val result = HybridBootstrapAttemptCommandBuildResult.EndpointTooOld(
            ageMillis = 45_000L,
            maxAgeMillis = 30_000L
        )

        assertEquals(
            "Hybrid bootstrap command: endpoint too old age=45000 max=30000",
            hybridBootstrapAttemptCommandBuildRuntimeStatusText(result)
        )
    }

    @Test
    fun notAllowedCommandBuildStatusTextMapsToStableRuntimeStatusText() {
        val result = HybridBootstrapAttemptCommandBuildResult.NotAllowed(
            reason = "Command creation timestamp is before request timestamp."
        )

        assertEquals(
            "Hybrid bootstrap command: not allowed: Command creation timestamp is before request timestamp.",
            hybridBootstrapAttemptCommandBuildRuntimeStatusText(result)
        )
    }

    @Test
    fun decisionComputationAfterHybridControlHandledDoesNotAppendGlobalChatMessage() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        val initialGlobalMessages = holder.uiState.globalMessages

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridOfferMessage(
                    sessionId = "hybrid-session-3",
                    createdAtMillis = 1_716_350_020L
                ),
                frameId = "hybrid-control-runtime-3",
                senderId = "peer-hybrid-3"
            )
        )

        assertNotNull(
            hybridBootstrapDecisionAfterReceiveOrNull(
                result = result,
                provider = provider
            )
        )
        assertEquals(initialGlobalMessages, holder.uiState.globalMessages)
    }

    @Test
    fun endpointResolutionComputationAfterHybridControlHandledDoesNotAppendGlobalChatMessage() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        val initialGlobalMessages = holder.uiState.globalMessages

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-endpoint-session-4",
                    createdAtMillis = 1_716_370_020L,
                    groupOwnerAddress = "192.168.49.55",
                    socketPort = 9055
                ),
                frameId = "hybrid-endpoint-no-global-append",
                senderId = "peer-hybrid-endpoint-4"
            )
        )

        assertNotNull(
            hybridBootstrapSocketEndpointResolutionAfterReceiveOrNull(
                result = result,
                provider = provider
            )
        )
        assertEquals(initialGlobalMessages, holder.uiState.globalMessages)
    }

    @Test
    fun attemptDecisionComputationAfterHybridControlHandledDoesNotAppendGlobalChatMessage() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        val initialGlobalMessages = holder.uiState.globalMessages

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-attempt-session-4",
                    createdAtMillis = 1_716_380_080L,
                    groupOwnerAddress = "192.168.49.65",
                    socketPort = 9065
                ),
                frameId = "hybrid-attempt-no-global-append",
                senderId = "peer-hybrid-attempt-4"
            )
        )

        assertNotNull(
            hybridBootstrapAttemptDecisionAfterReceiveOrNull(
                result = result,
                provider = provider,
                requestedAtMillis = 1_716_380_090L
            )
        )
        assertEquals(initialGlobalMessages, holder.uiState.globalMessages)
    }

    @Test
    fun diagnosticsComputationAfterHybridControlHandledDoesNotAppendGlobalChatMessage() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        val initialGlobalMessages = holder.uiState.globalMessages

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridOfferMessage(
                    sessionId = "hybrid-diagnostics-session-4",
                    createdAtMillis = 1_716_360_020L
                ),
                frameId = "hybrid-diagnostics-no-global-append",
                senderId = "peer-hybrid-diagnostics-4"
            )
        )

        assertNotNull(
            hybridBootstrapDiagnosticsAfterReceiveOrNull(
                result = result,
                provider = provider
            )
        )
        assertEquals(initialGlobalMessages, holder.uiState.globalMessages)
    }

    @Test
    fun decisionComputationAfterHybridControlHandledDoesNotAppendPrivateChatMessage() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        val initialPrivateMessages = holder.uiState.privateMessagesByPeerId

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-session-4",
                    createdAtMillis = 1_716_350_030L,
                    groupOwnerAddress = "192.168.49.23",
                    socketPort = 8990
                ),
                frameId = "hybrid-control-runtime-4",
                senderId = "peer-hybrid-4"
            )
        )

        assertNotNull(
            hybridBootstrapDecisionAfterReceiveOrNull(
                result = result,
                provider = provider
            )
        )
        assertEquals(initialPrivateMessages, holder.uiState.privateMessagesByPeerId)
    }

    @Test
    fun endpointResolutionComputationAfterHybridControlHandledDoesNotAppendPrivateChatMessage() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        val initialPrivateMessages = holder.uiState.privateMessagesByPeerId

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-endpoint-session-5",
                    createdAtMillis = 1_716_370_030L,
                    groupOwnerAddress = "192.168.49.56",
                    socketPort = 9056
                ),
                frameId = "hybrid-endpoint-no-private-append",
                senderId = "peer-hybrid-endpoint-5"
            )
        )

        assertNotNull(
            hybridBootstrapSocketEndpointResolutionAfterReceiveOrNull(
                result = result,
                provider = provider
            )
        )
        assertEquals(initialPrivateMessages, holder.uiState.privateMessagesByPeerId)
    }

    @Test
    fun attemptDecisionComputationAfterHybridControlHandledDoesNotAppendPrivateChatMessage() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        val initialPrivateMessages = holder.uiState.privateMessagesByPeerId

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-attempt-session-5",
                    createdAtMillis = 1_716_380_100L,
                    groupOwnerAddress = "192.168.49.66",
                    socketPort = 9066
                ),
                frameId = "hybrid-attempt-no-private-append",
                senderId = "peer-hybrid-attempt-5"
            )
        )

        assertNotNull(
            hybridBootstrapAttemptDecisionAfterReceiveOrNull(
                result = result,
                provider = provider,
                requestedAtMillis = 1_716_380_110L
            )
        )
        assertEquals(initialPrivateMessages, holder.uiState.privateMessagesByPeerId)
    }

    @Test
    fun diagnosticsComputationAfterHybridControlHandledDoesNotAppendPrivateChatMessage() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        val initialPrivateMessages = holder.uiState.privateMessagesByPeerId

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-diagnostics-session-5",
                    createdAtMillis = 1_716_360_030L,
                    groupOwnerAddress = "192.168.49.45",
                    socketPort = 9045
                ),
                frameId = "hybrid-diagnostics-no-private-append",
                senderId = "peer-hybrid-diagnostics-5"
            )
        )

        assertNotNull(
            hybridBootstrapDiagnosticsAfterReceiveOrNull(
                result = result,
                provider = provider
            )
        )
        assertEquals(initialPrivateMessages, holder.uiState.privateMessagesByPeerId)
    }

    @Test
    fun commandBuildComputationAfterHybridControlHandledDoesNotAppendGlobalChatMessage() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        val initialGlobalMessages = holder.uiState.globalMessages

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-command-session-4",
                    createdAtMillis = 1_716_390_080L,
                    groupOwnerAddress = "192.168.49.84",
                    socketPort = 9084
                ),
                frameId = "hybrid-command-no-global-append",
                senderId = "peer-hybrid-command-4"
            )
        )

        assertNotNull(
            hybridBootstrapAttemptCommandBuildResultAfterReceiveOrNull(
                result = result,
                provider = provider,
                requestedAtMillis = 1_716_390_090L,
                commandCreatedAtMillis = 1_716_390_091L
            )
        )
        assertEquals(initialGlobalMessages, holder.uiState.globalMessages)
    }

    @Test
    fun commandBuildComputationAfterHybridControlHandledDoesNotAppendPrivateChatMessage() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        val initialPrivateMessages = holder.uiState.privateMessagesByPeerId

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-command-session-5",
                    createdAtMillis = 1_716_390_100L,
                    groupOwnerAddress = "192.168.49.85",
                    socketPort = 9085
                ),
                frameId = "hybrid-command-no-private-append",
                senderId = "peer-hybrid-command-5"
            )
        )

        assertNotNull(
            hybridBootstrapAttemptCommandBuildResultAfterReceiveOrNull(
                result = result,
                provider = provider,
                requestedAtMillis = 1_716_390_110L,
                commandCreatedAtMillis = 1_716_390_111L
            )
        )
        assertEquals(initialPrivateMessages, holder.uiState.privateMessagesByPeerId)
    }

    @Test
    fun commandBuildComputationDoesNotCallHybridBootstrapCommandExecutorExecute() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = holder,
            hybridControlStore = store
        )
        val executor = RecordingHybridBootstrapCommandExecutor(
            result = HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-unused",
                sessionId = "session-unused",
                bootstrapIdentifier = "bootstrap-unused",
                groupOwnerAddress = "192.168.49.86",
                socketPort = 9086,
                commandCreatedAtMillis = 1_716_390_121L
            )
        )

        val result = receiveFrames(
            receiver = receiver,
            frames = hybridControlFrames(
                message = hybridSocketHintMessage(
                    sessionId = "hybrid-command-session-6",
                    createdAtMillis = 1_716_390_120L,
                    groupOwnerAddress = "192.168.49.86",
                    socketPort = 9086
                ),
                frameId = "hybrid-command-no-execute",
                senderId = "peer-hybrid-command-6"
            )
        )

        assertNotNull(
            hybridBootstrapAttemptCommandBuildResultAfterReceiveOrNull(
                result = result,
                provider = provider,
                requestedAtMillis = 1_716_390_130L,
                commandCreatedAtMillis = 1_716_390_131L
            )
        )
        assertEquals(0, executor.executeCallCount)
        assertTrue(executor.executedCommands.isEmpty())
    }

    @Test
    fun commandBuildRuntimeStatusHelperDoesNotMutateResult() {
        val result = HybridBootstrapAttemptCommandBuildResult.Built(
            HybridBootstrapAttemptCommand(
                peerId = "peer-command-stable",
                sessionId = "session-command-stable",
                bootstrapIdentifier = "bootstrap-command-stable",
                groupOwnerAddress = "192.168.49.87",
                socketPort = 9087,
                latestCreatedAtMillis = 1_716_390_140L,
                requestedAtMillis = 1_716_390_141L,
                commandCreatedAtMillis = 1_716_390_142L
            )
        )
        val before = result.copy(
            command = result.command.copy()
        )

        val statusText = hybridBootstrapAttemptCommandBuildRuntimeStatusText(result)

        assertEquals(
            "Hybrid bootstrap command: built peer=peer-command-stable session=session-command-stable address=192.168.49.87 port=9087",
            statusText
        )
        assertEquals(before, result)
    }

    @Test
    fun triggerRuntimeStatusHelperDoesNotMutateResult() {
        val result = HybridBootstrapCommandTriggerResult.Executed(
            HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-trigger-stable",
                sessionId = "session-trigger-stable",
                bootstrapIdentifier = "bootstrap-trigger-stable",
                groupOwnerAddress = "192.168.49.174",
                socketPort = 9174,
                commandCreatedAtMillis = 1_733_000_050L
            )
        )
        val before = result.copy(
            executionResult = HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-trigger-stable",
                sessionId = "session-trigger-stable",
                bootstrapIdentifier = "bootstrap-trigger-stable",
                groupOwnerAddress = "192.168.49.174",
                socketPort = 9174,
                commandCreatedAtMillis = 1_733_000_050L
            )
        )

        val statusText = hybridBootstrapCommandTriggerRuntimeStatusText(result)

        assertEquals(
            "Hybrid bootstrap trigger: accepted peer=peer-trigger-stable session=session-trigger-stable address=192.168.49.174 port=9174",
            statusText
        )
        assertEquals(before, result)
    }

    @Test
    fun attemptRuntimeStatusHelperDoesNotMutateDecision() {
        val decision = HybridBootstrapAttemptDecision.Allowed(
            HybridBootstrapAttemptRequest(
                peerId = "peer-attempt-stable",
                sessionId = "session-attempt-stable",
                bootstrapIdentifier = "bootstrap-attempt-stable",
                groupOwnerAddress = "192.168.49.67",
                socketPort = 9067,
                latestCreatedAtMillis = 1_716_380_120L,
                requestedAtMillis = 1_716_380_121L
            )
        )
        val before = decision.copy(
            request = decision.request.copy()
        )

        val statusText = hybridBootstrapAttemptRuntimeStatusText(decision)

        assertEquals(
            "Hybrid bootstrap attempt: allowed peer=peer-attempt-stable session=session-attempt-stable address=192.168.49.67 port=9067",
            statusText
        )
        assertEquals(before, decision)
    }

    @Test
    fun endpointRuntimeStatusHelperDoesNotMutateResolution() {
        val resolution = HybridBootstrapSocketEndpointResolution.Resolved(
            HybridBootstrapSocketEndpoint(
                peerId = "peer-endpoint-stable",
                sessionId = "session-endpoint-stable",
                bootstrapIdentifier = "bootstrap-endpoint-stable",
                groupOwnerAddress = "192.168.49.57",
                socketPort = 9057,
                latestCreatedAtMillis = 1_716_370_057L
            )
        )
        val before = resolution.copy(
            endpoint = resolution.endpoint.copy()
        )

        val statusText = hybridBootstrapSocketEndpointRuntimeStatusText(resolution)

        assertEquals(
            "Hybrid bootstrap endpoint: peer=peer-endpoint-stable session=session-endpoint-stable address=192.168.49.57 port=9057",
            statusText
        )
        assertEquals(before, resolution)
    }

    @Test
    fun runtimeStatusHelperDoesNotMutateDiagnostics() {
        val diagnostics = HybridBootstrapDiagnostics(
            candidateCount = 1,
            socketReadyCandidateCount = 1,
            selectionStatus = HybridBootstrapDiagnostics.SelectionStatus.Selected,
            selectedPeerId = "peer-stable",
            selectedSessionId = "session-stable",
            selectedGroupOwnerAddress = "192.168.49.60",
            selectedSocketPort = 9060,
            selectedLatestCreatedAtMillis = 1_716_360_060L,
            statusText = "Hybrid bootstrap candidate ready: peer=peer-stable session=session-stable address=192.168.49.60 port=9060"
        )
        val before = diagnostics.copy()

        val statusText = hybridBootstrapDiagnosticsRuntimeStatusText(diagnostics)

        assertEquals(
            "Hybrid bootstrap: socket-ready peer=peer-stable session=session-stable address=192.168.49.60 port=9060",
            statusText
        )
        assertEquals(before, diagnostics)
    }

    @Test
    fun runtimeStatusTextReportsIdentityHandlerUnavailable() {
        val result = BleTransportReceiveResult.Processed(
            groupId = 0x42,
            processingResult = IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable(
                message = incomingIdentityTransportMessage(senderId = "peer-alpha"),
                reason = "Local agreement private key unavailable: Android Keystore unavailable (KeyStoreException)"
            )
        )

        assertEquals(
            "Local agreement private key unavailable: Android Keystore unavailable (KeyStoreException)",
            identityExchangeRuntimeStatusText(result)
        )
    }

    @Test
    fun runtimeStatusTextReportsIdentityEstablished() {
        val result = BleTransportReceiveResult.Processed(
            groupId = 0x43,
            processingResult = IncomingTransportFrameProcessingResult.IdentityHandled(
                message = incomingIdentityTransportMessage(senderId = "peer-beta"),
                handlingResult = PeerIdentityExchangeHandlingResult.Established(
                    peerId = "peer-beta"
                )
            )
        )

        assertEquals(
            "Identity received from peer-beta. Send yours back from this device.",
            identityExchangeRuntimeStatusText(result)
        )
    }

    @Test
    fun runtimeStatusTextReportsReceiveFailureBeforeIdentityHandling() {
        val result = BleTransportReceiveResult.ProcessorFailed(
            groupId = 0x44,
            processingResult = IncomingTransportFrameProcessingResult.ReceiveFailed(
                receiveResult = IncomingTransportReceiveResult.InvalidEnvelope(
                    reason = "Envelope payload is malformed."
                )
            )
        )

        assertEquals(
            "Incoming transport invalid envelope: Envelope payload is malformed.",
            identityExchangeRuntimeStatusText(result)
        )
    }

    @Test
    fun runtimeIncomingStatusTextReportsPublicGlobalMessageReceived() {
        val result = BleTransportReceiveResult.Processed(
            groupId = 0x45,
            processingResult = IncomingTransportFrameProcessingResult.Received(
                message = IncomingTransportMessage(
                    frame = MessageFrame(
                        id = "incoming-chat-1",
                        type = MessageFrameType.GLOBAL_TEXT,
                        senderId = "peer-chat",
                        createdAtMillis = 1_716_400_001L,
                        payload = "hello public chat"
                    )
                ),
                ingestionResult = IncomingMessageIngestionResult.Appended(
                    message = ChatMessage(
                        id = "incoming-chat-1",
                        threadId = "global",
                        senderId = "peer-chat",
                        senderName = "peer-chat",
                        text = "hello public chat",
                        createdAtMillis = 1_716_400_001L,
                        status = MessageStatus.RECEIVED,
                        isOutgoing = false
                    )
                )
            )
        )

        assertEquals(
            "Received public global message from peer-chat.",
            incomingMessageRuntimeStatusText(result)
        )
    }

    private fun readyBluetoothStatus(
        isBluetoothEnabled: Boolean = true,
        isLocationEnabled: Boolean = true
    ): BluetoothPermissionStatus {
        return BluetoothPermissionStatus(
            requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            missingPermissions = emptySet(),
            isBluetoothEnabled = isBluetoothEnabled,
            isLocationEnabled = isLocationEnabled
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

    private fun privateMessage(
        peerId: String
    ): OutgoingChatMessage {
        return OutgoingChatMessage(
            messageId = "outgoing-$peerId",
            threadId = "private:$peerId",
            userText = "hello $peerId",
            createdAtMillis = 1_716_300_100L,
            status = MessageStatus.QUEUED
        )
    }

    private fun globalOutgoingMessage(
        messageId: String
    ): OutgoingChatMessage {
        return OutgoingChatMessage(
            messageId = messageId,
            threadId = "global",
            userText = "hello mesh",
            createdAtMillis = 1_716_300_101L,
            status = MessageStatus.QUEUED
        )
    }

    private fun reachableAuroraPeer(
        address: String,
        stableIdHex: String
    ): BleDiscoveredDevice {
        return BleDiscoveredDevice(
            address = address,
            name = "Aurora peer $stableIdHex",
            rssi = -45,
            isConnectable = true,
            hasAuroraDiscoveryPayload = true,
            stablePeerId = gr.hua.aurora.ble.discovery.BleStablePeerId.fromBytes(
                stableIdHex.chunked(2).map { byteHex ->
                    byteHex.toInt(16).toByte()
                }.toByteArray()
            )
        )
    }

    private class RecordingTransportSender(
        private val result: BleTransportSendResult
    ) : BleTransportSender {
        var capturedPlan: OutgoingBleTransportSendPlan? = null
        var sendCallCount: Int = 0
        val sentTargetPeerIds = mutableListOf<String?>()

        override fun send(
            plan: OutgoingBleTransportSendPlan,
            listener: BleTransportSender.Listener
        ) {
            sendCallCount += 1
            capturedPlan = plan
            sentTargetPeerIds += plan.targetPeerId
            listener.onSendResult(result)
        }
    }

    private class FakeOutgoingSessionMaterialProvider(
        private val materialByPeerId: Map<String, OutgoingMessageSendEncryptionMaterial> = emptyMap()
    ) : OutgoingSessionMaterialProvider {
        override fun encryptionMaterialFor(
            message: OutgoingChatMessage
        ): OutgoingMessageSendEncryptionMaterial? {
            return encryptionMaterialForTarget(
                message.threadId.removePrefix("private:")
            )
        }

        override fun encryptionMaterialForTarget(
            peerId: String
        ): OutgoingMessageSendEncryptionMaterial? {
            return materialByPeerId[peerId]
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

    private fun senderPublicKeyBytes(): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val publicKey = generator.generateKeyPair().public as ECPublicKey
        return Sec1PublicKeyEncoding.encodeUncompressed(publicKey)
    }

    private fun incomingIdentityTransportMessage(
        senderId: String
    ): IncomingTransportMessage {
        return IncomingTransportMessage(
            frame = MessageFrame(
                id = "identity-$senderId",
                type = MessageFrameType.IDENTITY_EXCHANGE,
                senderId = senderId,
                createdAtMillis = 1_716_200_001L,
                payload = "identity"
            ),
            senderPublicKey = senderPublicKeyBytes()
        )
    }

    private fun hybridOfferMessage(
        sessionId: String,
        createdAtMillis: Long
    ): HybridTransportControlMessage {
        return HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_OFFER,
            sessionId = sessionId,
            publicPeerIdHint = "peer-hybrid-hint",
            createdAtMillis = createdAtMillis,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP,
                HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
            )
        )
    }

    private fun hybridSocketHintMessage(
        sessionId: String,
        createdAtMillis: Long,
        groupOwnerAddress: String,
        socketPort: Int
    ): HybridTransportControlMessage {
        return HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_SOCKET_HINT,
            sessionId = sessionId,
            publicPeerIdHint = "peer-hybrid-hint",
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            createdAtMillis = createdAtMillis,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_SOCKET_HINT,
                HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
            )
        )
    }

    private fun hybridControlFrames(
        message: HybridTransportControlMessage,
        frameId: String,
        senderId: String,
        groupId: Int = 0x5100
    ): List<BleGattTransportFrame> {
        val frame = HybridTransportControlFrameFactory.create(
            message = message,
            frameId = frameId,
            senderId = senderId
        )
        return BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = MessageFrameCodec.encode(frame).toByteArray(UTF_8),
            groupId = groupId
        )
    }

    private fun receiveFrames(
        receiver: gr.hua.aurora.ble.transport.BleTransportFrameReceiver,
        frames: List<BleGattTransportFrame>
    ): BleTransportReceiveResult {
        return frames.fold<BleGattTransportFrame, BleTransportReceiveResult?>(null) { _, frame ->
            receiver.receive(frame)
        } ?: error("Expected at least one transport frame.")
    }

    private fun deterministicKey(offset: Int): ByteArray {
        return ByteArray(32) { index -> (index + offset).toByte() }
    }

    private fun testPrivateEncryptionMaterial(): OutgoingMessageSendEncryptionMaterial {
        return OutgoingMessageSendEncryptionMaterial(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = deterministicKey(191),
            authenticatedData = "private-connect-on-send".toByteArray(UTF_8)
        )
    }

    private fun decodePrivateFrame(
        plan: OutgoingBleTransportSendPlan,
        material: OutgoingMessageSendEncryptionMaterial
    ): MessageFrame {
        val envelopeBytes = gr.hua.aurora.ble.transport.BleGattTransportFrameReassembler.reassemble(
            plan.framesInSendOrder()
        )
        val envelope = EncryptedMessageEnvelopeCodec.decode(String(envelopeBytes, UTF_8))
        val frameBytes = gr.hua.aurora.protocol.EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = envelope,
            keyBytes = material.keyBytes,
            authenticatedData = material.authenticatedData
        )
        return MessageFrameCodec.decode(String(frameBytes, UTF_8))
    }

    private fun decodePlaintextFrame(
        plan: OutgoingBleTransportSendPlan
    ): MessageFrame {
        val frameBytes = gr.hua.aurora.ble.transport.BleGattTransportFrameReassembler.reassemble(
            plan.framesInSendOrder()
        )
        return MessageFrameCodec.decode(String(frameBytes, UTF_8))
    }

    private fun decodeWifiDirectMessageFrame(
        frame: WifiDirectTransportFrame
    ): MessageFrame {
        return MessageFrameCodec.decode(String(frame.payloadBytes(), UTF_8))
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
            "Suspending runtime mesh operation did not complete synchronously in the test harness."
        }.getOrThrow()
    }

    private class RecordingWifiDirectTransportSender(
        private val result: WifiDirectTransportSendResult
    ) : WifiDirectTransportSender {
        val sentFrames = mutableListOf<WifiDirectTransportFrame>()
        var sendCallCount: Int = 0

        override suspend fun send(
            frame: WifiDirectTransportFrame
        ): WifiDirectTransportSendResult {
            sendCallCount += 1
            sentFrames += WifiDirectTransportFrame.fromPayload(frame.payloadBytes())
            return result
        }
    }

    private class RecordingHybridBootstrapCommandExecutor(
        private val result: HybridBootstrapCommandExecutionResult
    ) : HybridBootstrapCommandExecutor {
        private val recordedCommands = mutableListOf<HybridBootstrapAttemptCommand>()
        var executeCallCount: Int = 0
            private set

        val executedCommands: List<HybridBootstrapAttemptCommand>
            get() = recordedCommands.toList()

        override fun execute(
            command: HybridBootstrapAttemptCommand
        ): HybridBootstrapCommandExecutionResult {
            executeCallCount += 1
            recordedCommands += command.copy()
            return result
        }
    }

    private fun builtHybridBootstrapAttemptCommandResult(
        peerId: String,
        sessionId: String,
        bootstrapIdentifier: String,
        groupOwnerAddress: String,
        socketPort: Int,
        latestCreatedAtMillis: Long,
        requestedAtMillis: Long,
        commandCreatedAtMillis: Long
    ): HybridBootstrapAttemptCommandBuildResult.Built {
        return HybridBootstrapAttemptCommandBuildResult.Built(
            HybridBootstrapAttemptCommand(
                peerId = peerId,
                sessionId = sessionId,
                bootstrapIdentifier = bootstrapIdentifier,
                groupOwnerAddress = groupOwnerAddress,
                socketPort = socketPort,
                latestCreatedAtMillis = latestCreatedAtMillis,
                requestedAtMillis = requestedAtMillis,
                commandCreatedAtMillis = commandCreatedAtMillis
            )
        )
    }

    private fun assertUnavailableManualTriggerSnapshotDoesNotInvokeAction(
        snapshot: HybridBootstrapManualTriggerSnapshot
    ) {
        var invokeCount = 0

        val result = triggerHybridBootstrapManuallyIfAvailable(
            snapshot = snapshot,
            manualTriggerAction = {
                invokeCount += 1
                HybridBootstrapCommandTriggerResult.NoCandidates
            }
        )

        assertEquals(0, invokeCount)
        assertEquals(
            HybridBootstrapCommandTriggerResult.NotAllowed(
                reason = "Manual hybrid bootstrap trigger is not available."
            ),
            result
        )
    }
}
