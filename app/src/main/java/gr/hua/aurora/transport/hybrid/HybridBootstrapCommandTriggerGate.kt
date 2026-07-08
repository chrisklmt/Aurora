package gr.hua.aurora.transport.hybrid

object HybridBootstrapCommandTriggerGate {
    fun trigger(
        buildResult: HybridBootstrapAttemptCommandBuildResult,
        executor: HybridBootstrapCommandExecutor
    ): HybridBootstrapCommandTriggerResult {
        return when (buildResult) {
            is HybridBootstrapAttemptCommandBuildResult.Built ->
                HybridBootstrapCommandTriggerResult.Executed(
                    executionResult = executor.execute(buildResult.command)
                )
            HybridBootstrapAttemptCommandBuildResult.NoCandidates ->
                HybridBootstrapCommandTriggerResult.NoCandidates
            HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate ->
                HybridBootstrapCommandTriggerResult.NoSocketReadyCandidate
            is HybridBootstrapAttemptCommandBuildResult.InvalidEndpoint ->
                HybridBootstrapCommandTriggerResult.InvalidEndpoint(
                    reason = buildResult.reason
                )
            is HybridBootstrapAttemptCommandBuildResult.EndpointTooOld ->
                HybridBootstrapCommandTriggerResult.EndpointTooOld(
                    ageMillis = buildResult.ageMillis,
                    maxAgeMillis = buildResult.maxAgeMillis
                )
            is HybridBootstrapAttemptCommandBuildResult.NotAllowed ->
                HybridBootstrapCommandTriggerResult.NotAllowed(
                    reason = buildResult.reason
                )
        }
    }
}
