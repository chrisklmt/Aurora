package gr.hua.aurora.transport.hybrid

object HybridBootstrapManualTriggerSnapshotFormatter {
    fun format(
        commandBuildResult: HybridBootstrapAttemptCommandBuildResult,
        latestTriggerResult: HybridBootstrapCommandTriggerResult?
    ): HybridBootstrapManualTriggerSnapshot {
        return HybridBootstrapManualTriggerSnapshot(
            commandBuildResult = commandBuildResult,
            latestTriggerResult = latestTriggerResult,
            canTriggerNow = commandBuildResult is HybridBootstrapAttemptCommandBuildResult.Built,
            commandStatusText = commandStatusText(commandBuildResult),
            triggerStatusText = latestTriggerResult?.let(::triggerStatusText)
        )
    }

    private fun commandStatusText(
        result: HybridBootstrapAttemptCommandBuildResult
    ): String {
        return when (result) {
            is HybridBootstrapAttemptCommandBuildResult.Built ->
                "Hybrid bootstrap command: built peer=${result.command.peerId} " +
                    "session=${result.command.sessionId} " +
                    "address=${result.command.groupOwnerAddress} " +
                    "port=${result.command.socketPort}"
            HybridBootstrapAttemptCommandBuildResult.NoCandidates ->
                "Hybrid bootstrap command: no candidates"
            HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate ->
                "Hybrid bootstrap command: no socket-ready candidate"
            is HybridBootstrapAttemptCommandBuildResult.InvalidEndpoint ->
                "Hybrid bootstrap command: invalid endpoint: ${result.reason}"
            is HybridBootstrapAttemptCommandBuildResult.EndpointTooOld ->
                "Hybrid bootstrap command: endpoint too old age=${result.ageMillis} max=${result.maxAgeMillis}"
            is HybridBootstrapAttemptCommandBuildResult.NotAllowed ->
                "Hybrid bootstrap command: not allowed: ${result.reason}"
        }
    }

    private fun triggerStatusText(
        result: HybridBootstrapCommandTriggerResult
    ): String {
        return when (result) {
            is HybridBootstrapCommandTriggerResult.Executed ->
                when (val executionResult = result.executionResult) {
                    is HybridBootstrapCommandExecutionResult.Accepted ->
                        "Hybrid bootstrap trigger: accepted peer=${executionResult.peerId} " +
                            "session=${executionResult.sessionId} " +
                            "address=${executionResult.groupOwnerAddress} " +
                            "port=${executionResult.socketPort}"
                    is HybridBootstrapCommandExecutionResult.Rejected ->
                        "Hybrid bootstrap trigger: rejected: ${executionResult.reason}"
                }
            HybridBootstrapCommandTriggerResult.NoCandidates ->
                "Hybrid bootstrap trigger: no candidates"
            HybridBootstrapCommandTriggerResult.NoSocketReadyCandidate ->
                "Hybrid bootstrap trigger: no socket-ready candidate"
            is HybridBootstrapCommandTriggerResult.InvalidEndpoint ->
                "Hybrid bootstrap trigger: invalid endpoint: ${result.reason}"
            is HybridBootstrapCommandTriggerResult.EndpointTooOld ->
                "Hybrid bootstrap trigger: endpoint too old age=${result.ageMillis} max=${result.maxAgeMillis}"
            is HybridBootstrapCommandTriggerResult.NotAllowed ->
                "Hybrid bootstrap trigger: not allowed: ${result.reason}"
        }
    }
}
