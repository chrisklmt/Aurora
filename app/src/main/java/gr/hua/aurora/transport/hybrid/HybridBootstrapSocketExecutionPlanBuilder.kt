package gr.hua.aurora.transport.hybrid

object HybridBootstrapSocketExecutionPlanBuilder {
    const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Long = 5_000L
    const val MAX_CONNECT_TIMEOUT_MILLIS: Long = 30_000L

    fun build(
        command: HybridBootstrapAttemptCommand,
        connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS
    ): HybridBootstrapSocketExecutionPlanBuildResult {
        validateTimeout(connectTimeoutMillis)?.let { reason ->
            return HybridBootstrapSocketExecutionPlanBuildResult.InvalidTimeout(reason)
        }

        validateCommand(command)?.let { reason ->
            return HybridBootstrapSocketExecutionPlanBuildResult.InvalidCommand(reason)
        }

        return HybridBootstrapSocketExecutionPlanBuildResult.Built(
            plan = HybridBootstrapSocketExecutionPlan(
                peerId = command.peerId,
                sessionId = command.sessionId,
                bootstrapIdentifier = command.bootstrapIdentifier,
                groupOwnerAddress = command.groupOwnerAddress,
                socketPort = command.socketPort,
                latestCreatedAtMillis = command.latestCreatedAtMillis,
                requestedAtMillis = command.requestedAtMillis,
                commandCreatedAtMillis = command.commandCreatedAtMillis,
                connectTimeoutMillis = connectTimeoutMillis
            )
        )
    }

    private fun validateTimeout(
        connectTimeoutMillis: Long
    ): String? {
        if (connectTimeoutMillis <= 0L) {
            return "Hybrid bootstrap socket execution plan connectTimeoutMillis must be greater than 0."
        }
        if (connectTimeoutMillis > MAX_CONNECT_TIMEOUT_MILLIS) {
            return "Hybrid bootstrap socket execution plan connectTimeoutMillis must be less than or equal to $MAX_CONNECT_TIMEOUT_MILLIS."
        }
        return null
    }

    private fun validateCommand(
        command: HybridBootstrapAttemptCommand
    ): String? {
        if (command.peerId.isBlank()) {
            return "Hybrid bootstrap socket execution plan peerId must not be blank."
        }
        if (command.sessionId.isBlank()) {
            return "Hybrid bootstrap socket execution plan sessionId must not be blank."
        }
        if (command.bootstrapIdentifier.isBlank()) {
            return "Hybrid bootstrap socket execution plan bootstrapIdentifier must not be blank."
        }
        if (command.groupOwnerAddress.isBlank()) {
            return "Hybrid bootstrap socket execution plan groupOwnerAddress must not be blank."
        }
        if (command.socketPort !in 1..65535) {
            return "Hybrid bootstrap socket execution plan socketPort must be in 1..65535."
        }
        if (command.latestCreatedAtMillis < 0L) {
            return "Hybrid bootstrap socket execution plan latestCreatedAtMillis must be non-negative."
        }
        if (command.requestedAtMillis < command.latestCreatedAtMillis) {
            return "Hybrid bootstrap socket execution plan requestedAtMillis must be greater than or equal to latestCreatedAtMillis."
        }
        if (command.commandCreatedAtMillis < command.requestedAtMillis) {
            return "Hybrid bootstrap socket execution plan commandCreatedAtMillis must be greater than or equal to requestedAtMillis."
        }
        return null
    }
}
