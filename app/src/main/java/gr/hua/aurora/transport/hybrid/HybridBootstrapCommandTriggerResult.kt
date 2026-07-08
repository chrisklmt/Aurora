package gr.hua.aurora.transport.hybrid

sealed interface HybridBootstrapCommandTriggerResult {
    data class Executed(
        val executionResult: HybridBootstrapCommandExecutionResult
    ) : HybridBootstrapCommandTriggerResult

    data object NoCandidates : HybridBootstrapCommandTriggerResult

    data object NoSocketReadyCandidate : HybridBootstrapCommandTriggerResult

    data class InvalidEndpoint(
        val reason: String
    ) : HybridBootstrapCommandTriggerResult

    data class EndpointTooOld(
        val ageMillis: Long,
        val maxAgeMillis: Long
    ) : HybridBootstrapCommandTriggerResult

    data class NotAllowed(
        val reason: String
    ) : HybridBootstrapCommandTriggerResult
}
