package gr.hua.aurora.transport.hybrid

data class HybridBootstrapManualTriggerSnapshot(
    val commandBuildResult: HybridBootstrapAttemptCommandBuildResult,
    val latestTriggerResult: HybridBootstrapCommandTriggerResult?,
    val canTriggerNow: Boolean,
    val commandStatusText: String,
    val triggerStatusText: String?
)
