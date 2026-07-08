package gr.hua.aurora.transport.hybrid

object HybridBootstrapAttemptCommandBuilder {
    fun build(
        decision: HybridBootstrapAttemptDecision,
        commandCreatedAtMillis: Long
    ): HybridBootstrapAttemptCommandBuildResult {
        return when (decision) {
            HybridBootstrapAttemptDecision.NoCandidates ->
                HybridBootstrapAttemptCommandBuildResult.NoCandidates
            HybridBootstrapAttemptDecision.NoSocketReadyCandidate ->
                HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate
            is HybridBootstrapAttemptDecision.InvalidEndpoint ->
                HybridBootstrapAttemptCommandBuildResult.InvalidEndpoint(
                    reason = decision.reason
                )
            is HybridBootstrapAttemptDecision.EndpointTooOld ->
                HybridBootstrapAttemptCommandBuildResult.EndpointTooOld(
                    ageMillis = decision.ageMillis,
                    maxAgeMillis = decision.maxAgeMillis
                )
            is HybridBootstrapAttemptDecision.Allowed ->
                buildAllowedCommand(
                    request = decision.request,
                    commandCreatedAtMillis = commandCreatedAtMillis
                )
        }
    }

    private fun buildAllowedCommand(
        request: HybridBootstrapAttemptRequest,
        commandCreatedAtMillis: Long
    ): HybridBootstrapAttemptCommandBuildResult {
        if (commandCreatedAtMillis < 0L) {
            return notAllowed(
                "Command creation timestamp is negative."
            )
        }
        if (commandCreatedAtMillis < request.requestedAtMillis) {
            return notAllowed(
                "Command creation timestamp is before request timestamp."
            )
        }

        return runCatching {
            HybridBootstrapAttemptCommandBuildResult.Built(
                command = HybridBootstrapAttemptCommand(
                    peerId = request.peerId,
                    sessionId = request.sessionId,
                    bootstrapIdentifier = request.bootstrapIdentifier,
                    groupOwnerAddress = request.groupOwnerAddress,
                    socketPort = request.socketPort,
                    latestCreatedAtMillis = request.latestCreatedAtMillis,
                    requestedAtMillis = request.requestedAtMillis,
                    commandCreatedAtMillis = commandCreatedAtMillis
                )
            )
        }.getOrElse { failure ->
            notAllowed(
                failure.message ?: "Hybrid bootstrap attempt command is invalid."
            )
        }
    }

    private fun notAllowed(
        reason: String
    ): HybridBootstrapAttemptCommandBuildResult.NotAllowed {
        return HybridBootstrapAttemptCommandBuildResult.NotAllowed(reason)
    }
}
