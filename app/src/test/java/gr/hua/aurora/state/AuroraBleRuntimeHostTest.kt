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
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
                "Public mesh connect-on-send: pending for 3032547611223344.",
                "Public mesh connect-on-send: succeeded for 3032547611223344."
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
    fun privateChatSendsImmediatelyWhenActivePeerMatchesSelectedContact() {
        val targetPeerId = "5032547611223344"
        val material = testPrivateEncryptionMaterial()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)

        val result = runSuspending {
            submitPrivateEncryptedMessage(
                message = privateMessage(targetPeerId),
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
    fun privateChatMarksFailedWhenTargetContactIsNotReachable() {
        val targetPeerId = "8032547611223344"
        val material = testPrivateEncryptionMaterial()
        val transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val wrongPeer = reachableAuroraPeer(
            address = "AA:BB:CC:00:00:07",
            stableIdHex = "9032547611223344"
        )

        val result = runSuspending {
            submitPrivateEncryptedMessage(
                message = privateMessage(targetPeerId),
                senderPeerId = "sender-private",
                senderUsername = "Alice",
                transportSender = transportSender,
                sessionMaterialProvider = FakeOutgoingSessionMaterialProvider(
                    materialByPeerId = mapOf(targetPeerId to material)
                ),
                activeTransportPeerId = wrongPeer.stablePeerId?.toByteArray()?.joinToString("") { byte ->
                    "%02x".format(byte.toInt() and 0xFF)
                },
                isActiveTransportConnected = true,
                reachablePeers = listOf(wrongPeer),
                connectToReachablePeer = {
                    error("private connect-on-send must not connect to a different reachable peer")
                }
            )
        }

        assertEquals(PrivateChatMessageSendResult.ContactNotReachable, result)
        assertEquals(0, transportSender.sendCallCount)
    }

    @Test
    fun privateChatDoesNotSendThroughWrongActivePeerWhenExactTargetIsReachable() {
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
        assertEquals(1, transportSender.sendCallCount)
        assertEquals(targetPeerId, requireNotNull(transportSender.capturedPlan).targetPeerId)
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
                )
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
                )
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
        val registry = gr.hua.aurora.protocol.PeerSessionRegistry()
        val handler = createAuroraIdentityHandlerOrNull(
            localIdentity = generateEcKeyPair().identity(),
            registry = registry
        )

        assertNotNull(handler)
    }

    @Test
    fun runtimeLeavesIdentityHandlerUnavailableWhenLocalIdentityMaterialIsMissing() {
        val registry = gr.hua.aurora.protocol.PeerSessionRegistry()
        val handler = createAuroraIdentityHandlerOrNull(
            localIdentity = null,
            registry = registry
        )

        assertNull(handler)
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

        override fun send(
            plan: OutgoingBleTransportSendPlan,
            listener: BleTransportSender.Listener
        ) {
            sendCallCount += 1
            capturedPlan = plan
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
}
