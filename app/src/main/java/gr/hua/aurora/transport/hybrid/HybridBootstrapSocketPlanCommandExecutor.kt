package gr.hua.aurora.transport.hybrid

class HybridBootstrapSocketPlanCommandExecutor(
    private val connector: HybridBootstrapSocketConnector,
    private val connectTimeoutMillis: Long =
        HybridBootstrapSocketExecutionPlanBuilder.DEFAULT_CONNECT_TIMEOUT_MILLIS
) : HybridBootstrapCommandExecutor {
    override fun execute(
        command: HybridBootstrapAttemptCommand
    ): HybridBootstrapCommandExecutionResult {
        return when (
            val buildResult = HybridBootstrapSocketExecutionPlanBuilder.build(
                command = command,
                connectTimeoutMillis = connectTimeoutMillis
            )
        ) {
            is HybridBootstrapSocketExecutionPlanBuildResult.Built -> {
                when (val connectionResult = connector.connect(buildResult.plan)) {
                    is HybridBootstrapSocketConnectionResult.Connected -> {
                        HybridBootstrapCommandExecutionResult.Accepted(
                            peerId = connectionResult.peerId,
                            sessionId = connectionResult.sessionId,
                            bootstrapIdentifier = connectionResult.bootstrapIdentifier,
                            groupOwnerAddress = connectionResult.groupOwnerAddress,
                            socketPort = connectionResult.socketPort,
                            commandCreatedAtMillis = buildResult.plan.commandCreatedAtMillis
                        )
                    }

                    is HybridBootstrapSocketConnectionResult.Failed -> {
                        HybridBootstrapCommandExecutionResult.Rejected(
                            reason = connectionResult.reason
                        )
                    }
                }
            }

            is HybridBootstrapSocketExecutionPlanBuildResult.InvalidCommand -> {
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Invalid socket execution command: ${buildResult.reason}"
                )
            }

            is HybridBootstrapSocketExecutionPlanBuildResult.InvalidTimeout -> {
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Invalid socket execution timeout: ${buildResult.reason}"
                )
            }
        }
    }
}
