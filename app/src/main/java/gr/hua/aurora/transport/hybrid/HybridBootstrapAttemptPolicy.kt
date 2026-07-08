package gr.hua.aurora.transport.hybrid

object HybridBootstrapAttemptPolicy {
    const val DEFAULT_MAX_ENDPOINT_AGE_MILLIS: Long = 30_000L

    fun decide(
        resolution: HybridBootstrapSocketEndpointResolution,
        requestedAtMillis: Long,
        maxEndpointAgeMillis: Long = DEFAULT_MAX_ENDPOINT_AGE_MILLIS
    ): HybridBootstrapAttemptDecision {
        if (requestedAtMillis < 0L) {
            return invalidDecision(
                "Requested at millis must be non-negative."
            )
        }
        if (maxEndpointAgeMillis < 0L) {
            return invalidDecision(
                "Max endpoint age millis must be non-negative."
            )
        }

        return when (resolution) {
            HybridBootstrapSocketEndpointResolution.NoCandidates ->
                HybridBootstrapAttemptDecision.NoCandidates
            HybridBootstrapSocketEndpointResolution.NoSocketReadyCandidate ->
                HybridBootstrapAttemptDecision.NoSocketReadyCandidate
            is HybridBootstrapSocketEndpointResolution.InvalidSelectedCandidate ->
                invalidDecision(resolution.reason)
            is HybridBootstrapSocketEndpointResolution.Resolved ->
                decideResolvedEndpoint(
                    endpoint = resolution.endpoint,
                    requestedAtMillis = requestedAtMillis,
                    maxEndpointAgeMillis = maxEndpointAgeMillis
                )
        }
    }

    private fun decideResolvedEndpoint(
        endpoint: HybridBootstrapSocketEndpoint,
        requestedAtMillis: Long,
        maxEndpointAgeMillis: Long
    ): HybridBootstrapAttemptDecision {
        val ageMillis = requestedAtMillis - endpoint.latestCreatedAtMillis
        if (ageMillis < 0L) {
            return invalidDecision(
                "Endpoint timestamp is in the future."
            )
        }
        if (ageMillis > maxEndpointAgeMillis) {
            return HybridBootstrapAttemptDecision.EndpointTooOld(
                ageMillis = ageMillis,
                maxAgeMillis = maxEndpointAgeMillis
            )
        }

        return runCatching {
            HybridBootstrapAttemptDecision.Allowed(
                request = HybridBootstrapAttemptRequest(
                    peerId = endpoint.peerId,
                    sessionId = endpoint.sessionId,
                    bootstrapIdentifier = endpoint.bootstrapIdentifier,
                    groupOwnerAddress = endpoint.groupOwnerAddress,
                    socketPort = endpoint.socketPort,
                    latestCreatedAtMillis = endpoint.latestCreatedAtMillis,
                    requestedAtMillis = requestedAtMillis
                )
            )
        }.getOrElse { failure ->
            invalidDecision(
                failure.message ?: "Hybrid bootstrap attempt request is invalid."
            )
        }
    }

    private fun invalidDecision(
        reason: String
    ): HybridBootstrapAttemptDecision.InvalidEndpoint {
        return HybridBootstrapAttemptDecision.InvalidEndpoint(reason)
    }
}
