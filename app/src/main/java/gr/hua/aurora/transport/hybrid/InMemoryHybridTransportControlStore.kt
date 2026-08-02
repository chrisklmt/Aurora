package gr.hua.aurora.transport.hybrid

class InMemoryHybridTransportControlStore : HybridTransportControlStore {
    private val stateLock = Any()
    private val sessionStatesByPeerId =
        linkedMapOf<String, LinkedHashMap<String, HybridTransportControlSessionState>>()

    override fun record(
        peerId: String,
        message: HybridTransportControlMessage
    ): HybridTransportControlStore.RecordResult {
        val sanitizedPeerId = peerId.trim()
        if (sanitizedPeerId.isEmpty()) {
            return HybridTransportControlStore.RecordResult.IgnoredInvalidPeerId
        }

        val storedMessage = message.deepCopy()

        return synchronized(stateLock) {
            val sessionsForPeer = sessionStatesByPeerId.getOrPut(sanitizedPeerId) {
                linkedMapOf()
            }
            val currentSessionState = sessionsForPeer[storedMessage.sessionId]
                ?: HybridTransportControlSessionState()

            val updatedSessionState = when (storedMessage.messageType) {
                HybridTransportControlMessage.MessageType.WIFI_DIRECT_OFFER -> {
                    currentSessionState.withNewerOfferOrNull(storedMessage)
                }

                HybridTransportControlMessage.MessageType.WIFI_DIRECT_ACCEPT -> {
                    currentSessionState.withNewerAcceptOrNull(storedMessage)
                }

                HybridTransportControlMessage.MessageType.WIFI_DIRECT_SOCKET_HINT -> {
                    currentSessionState.withNewerSocketHintOrNull(storedMessage)
                }

                HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_RUN_ANNOUNCE,
                HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_PARTICIPANT_JOIN,
                HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_PHASE_READY,
                HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_SERVER_READY -> {
                    null
                }

                HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_RUN_CANCEL,
                HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_RUN_COMPLETE -> {
                    null
                }
            }

            if (updatedSessionState == null) {
                if (
                    storedMessage.messageType ==
                    HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_SERVER_READY
                ) {
                    HybridTransportControlStore.RecordResult.IgnoredNonBootstrapMessageType
                } else {
                    HybridTransportControlStore.RecordResult.IgnoredOlderMessage
                }
            } else {
                sessionsForPeer[storedMessage.sessionId] = updatedSessionState
                HybridTransportControlStore.RecordResult.Stored
            }
        }
    }

    override fun snapshot(): HybridTransportControlState {
        return synchronized(stateLock) {
            HybridTransportControlState(
                sessionsByPeerId = sessionStatesByPeerId.mapValues { (_, sessionStates) ->
                    sessionStates.mapValues { (_, sessionState) ->
                        sessionState.deepCopy()
                    }.toMap()
                }.toMap()
            )
        }
    }

    override fun clear() {
        synchronized(stateLock) {
            sessionStatesByPeerId.clear()
        }
    }

    private fun HybridTransportControlSessionState.withNewerOfferOrNull(
        message: HybridTransportControlMessage
    ): HybridTransportControlSessionState? {
        if (!shouldReplace(existing = latestOffer, incoming = message)) {
            return null
        }

        return copy(latestOffer = message)
    }

    private fun HybridTransportControlSessionState.withNewerAcceptOrNull(
        message: HybridTransportControlMessage
    ): HybridTransportControlSessionState? {
        if (!shouldReplace(existing = latestAccept, incoming = message)) {
            return null
        }

        return copy(latestAccept = message)
    }

    private fun HybridTransportControlSessionState.withNewerSocketHintOrNull(
        message: HybridTransportControlMessage
    ): HybridTransportControlSessionState? {
        if (!shouldReplace(existing = latestSocketHint, incoming = message)) {
            return null
        }

        return copy(latestSocketHint = message)
    }

    private fun shouldReplace(
        existing: HybridTransportControlMessage?,
        incoming: HybridTransportControlMessage
    ): Boolean {
        return existing == null || incoming.createdAtMillis >= existing.createdAtMillis
    }

    private fun HybridTransportControlSessionState.deepCopy(): HybridTransportControlSessionState {
        return copy(
            latestOffer = latestOffer?.deepCopy(),
            latestAccept = latestAccept?.deepCopy(),
            latestSocketHint = latestSocketHint?.deepCopy()
        )
    }

    private fun HybridTransportControlMessage.deepCopy(): HybridTransportControlMessage {
        return copy(
            capabilityFlags = capabilityFlags.toSet()
        )
    }
}
