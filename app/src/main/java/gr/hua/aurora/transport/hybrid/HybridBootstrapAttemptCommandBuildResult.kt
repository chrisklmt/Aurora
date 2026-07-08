package gr.hua.aurora.transport.hybrid

sealed interface HybridBootstrapAttemptCommandBuildResult {
    data class Built(
        val command: HybridBootstrapAttemptCommand
    ) : HybridBootstrapAttemptCommandBuildResult

    data object NoCandidates : HybridBootstrapAttemptCommandBuildResult

    data object NoSocketReadyCandidate : HybridBootstrapAttemptCommandBuildResult

    data class InvalidEndpoint(
        val reason: String
    ) : HybridBootstrapAttemptCommandBuildResult

    data class EndpointTooOld(
        val ageMillis: Long,
        val maxAgeMillis: Long
    ) : HybridBootstrapAttemptCommandBuildResult

    data class NotAllowed(
        val reason: String
    ) : HybridBootstrapAttemptCommandBuildResult
}
