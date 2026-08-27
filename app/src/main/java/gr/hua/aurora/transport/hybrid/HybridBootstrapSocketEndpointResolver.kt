package gr.hua.aurora.transport.hybrid

object HybridBootstrapSocketEndpointResolver {
    fun resolve(
        decision: HybridBootstrapDecision,
        socketHintObservation: HybridBootstrapSocketHintObservation? = null
    ): HybridBootstrapSocketEndpointResolution {
        return when (val selection = decision.selection) {
            HybridBootstrapCandidateSelection.NoCandidates ->
                HybridBootstrapSocketEndpointResolution.NoCandidates
            HybridBootstrapCandidateSelection.NoSocketReadyCandidates ->
                HybridBootstrapSocketEndpointResolution.NoSocketReadyCandidate
            is HybridBootstrapCandidateSelection.Selected ->
                resolveSelectedCandidate(
                    candidate = selection.candidate,
                    socketHintObservation = socketHintObservation
                )
        }
    }

    private fun resolveSelectedCandidate(
        candidate: HybridBootstrapCandidate,
        socketHintObservation: HybridBootstrapSocketHintObservation?
    ): HybridBootstrapSocketEndpointResolution {
        if (!candidate.socketReady) {
            return invalidResolution(
                "Selected hybrid bootstrap candidate is not socket-ready."
            )
        }
        if (candidate.peerId.isBlank()) {
            return invalidResolution(
                "Selected hybrid bootstrap candidate peerId is blank."
            )
        }
        if (candidate.sessionId.isBlank()) {
            return invalidResolution(
                "Selected hybrid bootstrap candidate sessionId is blank."
            )
        }
        if (candidate.bootstrapIdentifier.isBlank()) {
            return invalidResolution(
                "Selected hybrid bootstrap candidate bootstrapIdentifier is blank."
            )
        }

        val groupOwnerAddress = candidate.groupOwnerAddress
            ?: return invalidResolution(
                "Selected hybrid bootstrap candidate groupOwnerAddress is missing."
            )
        if (groupOwnerAddress.isBlank()) {
            return invalidResolution(
                "Selected hybrid bootstrap candidate groupOwnerAddress is blank."
            )
        }

        val socketPort = candidate.socketPort
            ?: return invalidResolution(
                "Selected hybrid bootstrap candidate socketPort is missing."
            )
        if (socketPort !in 1..65535) {
            return invalidResolution(
                "Selected hybrid bootstrap candidate socketPort is out of range."
            )
        }
        if (candidate.latestCreatedAtMillis < 0L) {
            return invalidResolution(
                "Selected hybrid bootstrap candidate latestCreatedAtMillis is negative."
            )
        }

        return HybridBootstrapSocketEndpointResolution.Resolved(
            endpoint = HybridBootstrapSocketEndpoint(
                peerId = candidate.peerId,
                sessionId = candidate.sessionId,
                bootstrapIdentifier = candidate.bootstrapIdentifier,
                groupOwnerAddress = groupOwnerAddress,
                socketPort = socketPort,
                latestCreatedAtMillis = candidate.latestCreatedAtMillis,
                localSocketHintObservedAtMonotonicMillis =
                    socketHintObservation
                        ?.takeIf {
                            matchesSocketHintObservation(
                                candidate = candidate,
                                observation = it
                            )
                        }
                        ?.observedAtMonotonicMillis
            )
        )
    }

    private fun matchesSocketHintObservation(
        candidate: HybridBootstrapCandidate,
        observation: HybridBootstrapSocketHintObservation
    ): Boolean {
        return candidate.peerId == observation.peerId &&
            candidate.sessionId == observation.sessionId &&
            candidate.groupOwnerAddress == observation.groupOwnerAddress &&
            candidate.socketPort == observation.socketPort
    }

    private fun invalidResolution(
        reason: String
    ): HybridBootstrapSocketEndpointResolution.InvalidSelectedCandidate {
        return HybridBootstrapSocketEndpointResolution.InvalidSelectedCandidate(
            reason = reason
        )
    }
}
