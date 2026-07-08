package gr.hua.aurora.transport.hybrid

class HybridBootstrapCommandTriggerController(
    private val executor: HybridBootstrapCommandExecutor
) {
    private val recordedResults = mutableListOf<HybridBootstrapCommandTriggerResult>()

    var latestResult: HybridBootstrapCommandTriggerResult? = null
        private set

    val triggerHistory: List<HybridBootstrapCommandTriggerResult>
        get() = recordedResults.toList()

    fun trigger(
        buildResult: HybridBootstrapAttemptCommandBuildResult
    ): HybridBootstrapCommandTriggerResult {
        val result = HybridBootstrapCommandTriggerGate.trigger(
            buildResult = buildResult,
            executor = executor
        )
        latestResult = result
        recordedResults += result
        return result
    }
}
