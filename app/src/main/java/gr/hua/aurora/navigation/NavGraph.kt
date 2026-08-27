package gr.hua.aurora.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsRunner
import gr.hua.aurora.protocol.hasSessionForPeer
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.state.AuroraBleRuntimeState
import gr.hua.aurora.state.AuroraStateHolder
import gr.hua.aurora.state.retryGlobalChatMessageThroughProductionPath
import gr.hua.aurora.state.retryPrivateChatMessageThroughProductionPath
import gr.hua.aurora.state.sendGlobalChatMessageThroughProductionPath
import gr.hua.aurora.state.sendPrivateChatMessageThroughProductionPath
import gr.hua.aurora.ui.screens.AutomatedDiagnosticsScreen
import gr.hua.aurora.ui.screens.nearbyContactPeerId
import gr.hua.aurora.ui.screens.ContactsScreen
import gr.hua.aurora.ui.screens.GlobalChatScreen
import gr.hua.aurora.ui.screens.NearbyDevicesScreen
import gr.hua.aurora.ui.screens.PrivateChatScreen
import gr.hua.aurora.ui.screens.SettingsScreen
import gr.hua.aurora.wifidirect.socket.RememberedWifiDirectSocketState
import kotlinx.coroutines.launch

@Composable
internal fun NavGraph(
    navController: NavHostController,
    stateHolder: AuroraStateHolder,
    automatedDiagnosticsRunner: AutomatedDiagnosticsRunner,
    bleRuntimeState: AuroraBleRuntimeState,
    wifiDirectSocketState: RememberedWifiDirectSocketState,
    modifier: Modifier = Modifier
) {
    val uiState = stateHolder.uiState
    val sendScope = rememberCoroutineScope()
    val automatedDiagnosticsState by automatedDiagnosticsRunner.state.collectAsState()
    val nearbyVisiblePeerIds = bleRuntimeState.discoveredAuroraPeers
        .mapNotNull(::nearbyContactPeerId)
        .toSet()
    val onResetLocalData: () -> Unit = {
        wifiDirectSocketState.disableGlobalDebugSend()
        wifiDirectSocketState.disablePrivateDebugSend()
        wifiDirectSocketState.disableSendBridge()
        wifiDirectSocketState.disableReceiveBridge()
        bleRuntimeState.resetLocalIdentityAndSessions()
        stateHolder.resetLocalData()
    }
    val onNavigateBackOrGlobal: () -> Unit = {
        if (!navController.popBackStack()) {
            navController.navigate(Routes.GLOBAL) {
                launchSingleTop = true
                popUpTo(Routes.GLOBAL) {
                    inclusive = false
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.GLOBAL,
        modifier = modifier
    ) {
        composable(Routes.GLOBAL) {
            GlobalChatScreen(
                currentUsername = uiState.globalChatUsername,
                messages = uiState.globalMessages,
                queuedOutgoingCount = uiState.globalPendingOutgoingCount,
                transportSenderSourceLabel = bleRuntimeState.transportSenderSourceLabel,
                globalMeshDiagnostics = bleRuntimeState.globalMeshDiagnostics,
                lastIncomingMessageStatus = bleRuntimeState.lastIncomingMessageStatus,
                lastConnectOnSendStatus = bleRuntimeState.lastConnectOnSendStatus,
                lastGlobalMeshStatus = bleRuntimeState.lastGlobalMeshStatus,
                meshDeliveryResult = uiState.globalMeshDeliveryResult,
                showDebugDiagnostics = uiState.isDebugModeEnabled,
                onOpenContacts = {
                    navController.navigate(Routes.CONTACTS) {
                        launchSingleTop = true
                    }
                },
                onOpenNearby = { navController.navigate(Routes.NEARBY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                desiredAvailability = uiState.desiredAvailability,
                onDesiredAvailabilityChange = { preference: AuroraAvailabilityPreference ->
                    stateHolder.updateDesiredAvailability(preference)
                },
                onSendMessage = { text ->
                    sendScope.launch {
                        sendGlobalChatMessageThroughProductionPath(
                            text = text,
                            stateHolder = stateHolder,
                            currentUsername = { stateHolder.uiState.globalChatUsername },
                            submitTransport = { message, senderId ->
                                bleRuntimeState.submitGlobalMeshMessageWithOptionalWifiDirect(
                                    message,
                                    senderId,
                                    if (wifiDirectSocketState.globalDebugSendDiagnostics.enabled) {
                                        null
                                    } else {
                                        wifiDirectSocketState.transportSender
                                    }
                                )
                            },
                            submitWifiDirectDebugTransport =
                            if (wifiDirectSocketState.globalDebugSendDiagnostics.enabled) {
                                wifiDirectSocketState.sendGlobalDebugMessage
                            } else {
                                null
                            }
                        )
                    }
                },
                onRetryMessage = { messageId ->
                    sendScope.launch {
                        retryGlobalChatMessageThroughProductionPath(
                            messageId = messageId,
                            stateHolder = stateHolder,
                            currentUsername = { stateHolder.uiState.globalChatUsername },
                            submitTransport = { message, senderId ->
                                bleRuntimeState.submitGlobalMeshMessageWithOptionalWifiDirect(
                                    message,
                                    senderId,
                                    if (wifiDirectSocketState.globalDebugSendDiagnostics.enabled) {
                                        null
                                    } else {
                                        wifiDirectSocketState.transportSender
                                    }
                                )
                            },
                            submitWifiDirectDebugTransport =
                            if (wifiDirectSocketState.globalDebugSendDiagnostics.enabled) {
                                wifiDirectSocketState.sendGlobalDebugMessage
                            } else {
                                null
                            }
                        )
                    }
                },
                onResetLocalData = onResetLocalData
            )
        }

        composable(Routes.CONTACTS) {
            ContactsScreen(
                contacts = uiState.contacts,
                privateChatIdentitiesByPeerId = uiState.privateChatIdentitiesByPeerId,
                currentUsername = uiState.privateProfileUsername,
                desiredAvailability = uiState.desiredAvailability,
                automatedDiagnosticsState = automatedDiagnosticsState,
                nearbyVisiblePeerIds = nearbyVisiblePeerIds,
                showDebugDiagnostics = uiState.isDebugModeEnabled,
                peerSessionDiagnostics = bleRuntimeState.peerSessionDiagnostics,
                lastIdentityExchangeStatus = bleRuntimeState.lastIdentityExchangeStatus,
                onOpenAutomatedDiagnostics = {
                    navController.navigate(Routes.AUTOMATED_DIAGNOSTICS)
                },
                onOpenChat = { peerId ->
                    stateHolder.selectSecurePeer(peerId)
                    navController.navigate(Routes.privateChat(peerId))
                },
                onRenameChat = { peerId, customName ->
                    stateHolder.renamePrivateChat(peerId, customName)
                },
                onDeleteChat = { peerId ->
                    stateHolder.deletePrivateChat(peerId)
                    bleRuntimeState.clearSessionForPeer(peerId)
                },
                onResetLocalData = onResetLocalData,
                onBack = onNavigateBackOrGlobal
            )
        }

        composable(Routes.PRIVATE_ROUTE) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString(Routes.PRIVATE_ARG) ?: "unknown-peer"
            val selectedContact = stateHolder.findContactByPeerId(peerId)
            val privateChatIdentity = stateHolder.privateChatIdentityForPeerId(peerId)
            val hasRuntimeSession = bleRuntimeState.peerSessionDiagnostics.hasSessionForPeer(peerId)
            PrivateChatScreen(
                requestedPeerId = peerId,
                contact = selectedContact,
                privateChatIdentity = privateChatIdentity,
                hasRuntimeSession = hasRuntimeSession,
                isNearbyVisible = nearbyVisiblePeerIds.contains(peerId),
                currentUsername = uiState.privateProfileUsername,
                automatedDiagnosticsState = automatedDiagnosticsState,
                messages = stateHolder.privateMessagesForPeerId(peerId),
                lastDeliveryResult = stateHolder.latestPrivateChatDeliveryResultForPeerId(peerId),
                showDebugDiagnostics = uiState.isDebugModeEnabled,
                peerSessionDiagnostics = bleRuntimeState.peerSessionDiagnostics,
                activeTransportPeerId = bleRuntimeState.activeTransportPeerId,
                lastIdentityExchangeStatus = bleRuntimeState.lastIdentityExchangeStatus,
                wifiDirectRuntimeStatus = bleRuntimeState.wifiDirectRuntimeStatus,
                wifiDirectSocketDiagnostics = wifiDirectSocketState.diagnostics,
                wifiDirectAdapterDiagnostics = wifiDirectSocketState.adapterDiagnostics,
                wifiDirectSendBridgeDiagnostics = wifiDirectSocketState.sendBridgeDiagnostics,
                wifiDirectPrivateDebugSendDiagnostics = wifiDirectSocketState.privateDebugSendDiagnostics,
                wifiDirectReceiveBridgeDiagnostics = wifiDirectSocketState.receiveBridgeDiagnostics,
                onSetPrivateWifiDirectDebugSendEnabled = wifiDirectSocketState.setPrivateDebugSendEnabled,
                onOpenAutomatedDiagnostics = {
                    navController.navigate(Routes.AUTOMATED_DIAGNOSTICS)
                },
                onBack = onNavigateBackOrGlobal,
                onSendMessage = { text ->
                    sendScope.launch {
                        sendPrivateChatMessageThroughProductionPath(
                            peerId = peerId,
                            text = text,
                            stateHolder = stateHolder,
                            currentUsername = { stateHolder.uiState.privateProfileUsername },
                            resolvePrivateChatId = { targetPeerId ->
                                stateHolder.privateChatIdentityForPeerId(targetPeerId)?.privateChatId
                            },
                            submitTransport = bleRuntimeState.submitPrivateChatMessage,
                            submitWifiDirectDebugTransport =
                            if (wifiDirectSocketState.privateDebugSendDiagnostics.enabled) {
                                wifiDirectSocketState.sendPrivateDebugMessage
                            } else {
                                null
                            }
                        )
                    }
                },
                onRetryMessage = { messageId ->
                    sendScope.launch {
                        retryPrivateChatMessageThroughProductionPath(
                            peerId = peerId,
                            messageId = messageId,
                            stateHolder = stateHolder,
                            currentUsername = { stateHolder.uiState.privateProfileUsername },
                            resolvePrivateChatId = { targetPeerId ->
                                stateHolder.privateChatIdentityForPeerId(targetPeerId)?.privateChatId
                            },
                            submitTransport = bleRuntimeState.submitPrivateChatMessage,
                            submitWifiDirectDebugTransport =
                            if (wifiDirectSocketState.privateDebugSendDiagnostics.enabled) {
                                wifiDirectSocketState.sendPrivateDebugMessage
                            } else {
                                null
                            }
                        )
                    }
                },
                onResetLocalData = onResetLocalData
            )
        }

        composable(Routes.NEARBY) {
            NearbyDevicesScreen(
                nearbyDevices = uiState.nearbyDevices,
                contacts = uiState.contacts,
                currentUsername = uiState.privateProfileUsername,
                desiredAvailability = uiState.desiredAvailability,
                automatedDiagnosticsState = automatedDiagnosticsState,
                bleAdvertiseStatus = bleRuntimeState.bleAdvertiseStatus,
                bleGattServerStatus = bleRuntimeState.bleGattServerStatus,
                bleScanStatus = bleRuntimeState.bleScanStatus,
                bleScanDiagnostics = bleRuntimeState.bleScanDiagnostics,
                discoveredBleDevices = bleRuntimeState.discoveredAuroraPeers,
                showDebugDiagnostics = uiState.isDebugModeEnabled,
                bleConnectionStatus = bleRuntimeState.bleConnectionStatus,
                bleConnector = bleRuntimeState.bleConnector,
                privateChatIdentitiesByPeerId = uiState.privateChatIdentitiesByPeerId,
                transportSenderSourceLabel = bleRuntimeState.transportSenderSourceLabel,
                wifiDirectRuntimeStatus = bleRuntimeState.wifiDirectRuntimeStatus,
                wifiDirectSocketState = wifiDirectSocketState,
                onRefreshWifiDirectStatus = bleRuntimeState.refreshWifiDirectStatus,
                onStartWifiDirectDiscovery = bleRuntimeState.startWifiDirectDiscovery,
                onStopWifiDirectDiscovery = bleRuntimeState.stopWifiDirectDiscovery,
                onConnectWifiDirectPeer = bleRuntimeState.connectToWifiDirectPeer,
                onDisconnectWifiDirectPeer = bleRuntimeState.disconnectWifiDirectPeer,
                identityHandlerStatus = bleRuntimeState.identityHandlerStatus,
                hybridBootstrapJavaNetRuntimeEnabled =
                bleRuntimeState.hybridBootstrapJavaNetRuntimeEnabled,
                hybridBootstrapCommandExecutorMode =
                bleRuntimeState.hybridBootstrapCommandExecutorMode,
                hybridBootstrapDecision =
                bleRuntimeState.hybridBootstrapDecision,
                hybridBootstrapDiagnostics =
                bleRuntimeState.hybridBootstrapDiagnostics,
                hybridBootstrapManualTriggerSnapshot =
                bleRuntimeState.hybridBootstrapManualTriggerSnapshot,
                onHybridBootstrapManualTriggerRequested =
                bleRuntimeState.onHybridBootstrapManualTriggerRequested,
                hybridBootstrapManualAcceptAvailable =
                bleRuntimeState.hybridBootstrapManualAcceptAvailable,
                hybridBootstrapManualAcceptBlockedReason =
                bleRuntimeState.hybridBootstrapManualAcceptBlockedReason,
                lastHybridBootstrapManualAcceptStatus =
                bleRuntimeState.lastHybridBootstrapManualAcceptStatus,
                onHybridBootstrapManualAcceptRequested =
                bleRuntimeState.onHybridBootstrapManualAcceptRequested,
                hybridBootstrapManualOfferAvailable =
                bleRuntimeState.hybridBootstrapManualOfferAvailable,
                hybridBootstrapManualOfferBlockedReason =
                bleRuntimeState.hybridBootstrapManualOfferBlockedReason,
                lastHybridBootstrapManualOfferStatus =
                bleRuntimeState.lastHybridBootstrapManualOfferStatus,
                onHybridBootstrapManualOfferRequested =
                bleRuntimeState.onHybridBootstrapManualOfferRequested,
                peerSessionDiagnostics = bleRuntimeState.peerSessionDiagnostics,
                activeTransportPeerId = bleRuntimeState.activeTransportPeerId,
                activeTransportDeviceAddress = bleRuntimeState.activeTransportDeviceAddress,
                selectedSecurePeerId = uiState.selectedSecurePeerId,
                lastIdentityExchangeStatus = bleRuntimeState.lastIdentityExchangeStatus,
                onExchangeIdentityWithPeer = bleRuntimeState.exchangeIdentityWithPeer,
                onConnectTransportPeer = bleRuntimeState.connectToTransportPeer,
                onDisconnectTransportPeer = bleRuntimeState.disconnectTransportPeer,
                onAddOrUpdateContact = { peerId, displayName, lastSeenMillis, hasSession ->
                    stateHolder.addOrUpdateContact(
                        canonicalPeerId = peerId,
                        displayName = displayName,
                        lastSeenMillis = lastSeenMillis,
                        hasSession = hasSession
                    )
                    stateHolder.privateChatIdentityForPeerId(peerId)?.localProposalId
                },
                onPromoteContactSession = { peerId, displayName, lastSeenMillis ->
                    stateHolder.promoteContactSession(
                        canonicalPeerId = peerId,
                        displayName = displayName,
                        lastSeenMillis = lastSeenMillis
                    )
                },
                onRefreshContactLastSeen = { peerId, lastSeenMillis ->
                    stateHolder.refreshContactLastSeen(
                        canonicalPeerId = peerId,
                        lastSeenMillis = lastSeenMillis
                    )
                },
                onSelectSecurePeer = stateHolder::selectSecurePeer,
                onClearSelectedSecurePeer = stateHolder::clearSelectedSecurePeer,
                onOpenPrivateChat = { peerId ->
                    stateHolder.selectSecurePeer(peerId)
                    navController.navigate(Routes.privateChat(peerId))
                },
                onOpenAutomatedDiagnostics = {
                    navController.navigate(Routes.AUTOMATED_DIAGNOSTICS)
                },
                onResetLocalData = onResetLocalData,
                onBack = onNavigateBackOrGlobal
            )
        }

        composable(Routes.AUTOMATED_DIAGNOSTICS) {
            AutomatedDiagnosticsScreen(
                runner = automatedDiagnosticsRunner,
                currentUsername = uiState.privateProfileUsername,
                onRefreshWifiDirectStatus = bleRuntimeState.refreshWifiDirectStatus,
                onRefreshBluetoothStatus = bleRuntimeState.refreshBluetoothStatus,
                onBack = onNavigateBackOrGlobal
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                currentUsername = uiState.privateProfileUsername,
                generatedUsername = uiState.generatedUsername,
                useCustomUsernameInGlobalChat = uiState.useCustomUsernameInGlobalChat,
                isDebugModeEnabled = uiState.isDebugModeEnabled,
                onUsernameChange = stateHolder::updateUsername,
                onUseCustomUsernameInGlobalChatChange = stateHolder::updateUseCustomUsernameInGlobalChat,
                onDebugModeChange = stateHolder::updateDebugMode,
                onClearLocalData = onResetLocalData,
                onBack = onNavigateBackOrGlobal
            )
        }
    }
}
