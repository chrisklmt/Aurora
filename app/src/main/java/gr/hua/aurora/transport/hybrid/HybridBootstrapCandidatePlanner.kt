package gr.hua.aurora.transport.hybrid

object HybridBootstrapCandidatePlanner {
    fun plan(
        state: HybridTransportControlState
    ): List<HybridBootstrapCandidate> {
        return state.sessionsByPeerId.entries
            .asSequence()
            .flatMap { (peerId, sessionsBySessionId) ->
                sessionsBySessionId.entries.asSequence().mapNotNull { (sessionId, sessionState) ->
                    candidateOrNull(
                        peerId = peerId,
                        sessionId = sessionId,
                        sessionState = sessionState
                    )
                }
            }
            .sortedWith(
                compareByDescending<HybridBootstrapCandidate> { it.socketReady }
                    .thenByDescending { it.latestCreatedAtMillis }
                    .thenBy { it.peerId }
                    .thenBy { it.sessionId }
            )
            .toList()
    }

    private fun candidateOrNull(
        peerId: String,
        sessionId: String,
        sessionState: HybridTransportControlSessionState
    ): HybridBootstrapCandidate? {
        val sanitizedPeerId = peerId.trim()
        val latestCreatedAtMillis = listOfNotNull(
            sessionState.latestOffer?.createdAtMillis,
            sessionState.latestAccept?.createdAtMillis,
            sessionState.latestSocketHint?.createdAtMillis
        ).maxOrNull() ?: return null
        if (sanitizedPeerId.isEmpty() || sessionId.isBlank()) {
            return null
        }

        val latestHintedMessage = listOfNotNull(
            sessionState.latestOffer,
            sessionState.latestAccept,
            sessionState.latestSocketHint
        )
            .sortedByDescending(HybridTransportControlMessage::createdAtMillis)
            .firstOrNull { !it.publicPeerIdHint.isNullOrBlank() }

        val bootstrapIdentifier = resolvedBootstrapIdentifier(
            sessionId = sessionId,
            sessionState = sessionState
        )
        val latestSocketHint = sessionState.latestSocketHint
        val groupOwnerAddress = latestSocketHint?.groupOwnerAddress
        val socketPort = latestSocketHint?.socketPort

        return HybridBootstrapCandidate(
            peerId = sanitizedPeerId,
            sessionId = sessionId,
            bootstrapIdentifier = bootstrapIdentifier,
            publicPeerIdHint = latestHintedMessage?.publicPeerIdHint,
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            latestCreatedAtMillis = latestCreatedAtMillis,
            hasOffer = sessionState.latestOffer != null,
            hasAccept = sessionState.latestAccept != null,
            hasSocketHint = latestSocketHint != null,
            socketReady = latestSocketHint != null &&
                !groupOwnerAddress.isNullOrBlank() &&
                socketPort != null &&
                socketPort in 1..65535 &&
                sessionId.isNotBlank() &&
                bootstrapIdentifier.isNotBlank()
        )
    }

    private fun resolvedBootstrapIdentifier(
        sessionId: String,
        sessionState: HybridTransportControlSessionState
    ): String {
        // Σε αυτό το στάδιο το sessionId είναι το μοναδικό bootstrap identifier που εκθέτει το πρωτόκολλο.
        return listOfNotNull(
            sessionState.latestSocketHint?.sessionId,
            sessionState.latestAccept?.sessionId,
            sessionState.latestOffer?.sessionId
        ).firstOrNull { it.isNotBlank() } ?: sessionId
    }
}
