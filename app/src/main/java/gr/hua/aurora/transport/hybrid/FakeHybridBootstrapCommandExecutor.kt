package gr.hua.aurora.transport.hybrid

class FakeHybridBootstrapCommandExecutor(
    private val shouldAccept: Boolean = true,
    private val rejectionReason: String =
        "Fake hybrid bootstrap executor rejected command."
) : HybridBootstrapCommandExecutor {
    private val recordedCommands = mutableListOf<HybridBootstrapAttemptCommand>()

    val executedCommands: List<HybridBootstrapAttemptCommand>
        get() = recordedCommands.toList()

    override fun execute(
        command: HybridBootstrapAttemptCommand
    ): HybridBootstrapCommandExecutionResult {
        recordedCommands += command

        if (!shouldAccept) {
            return HybridBootstrapCommandExecutionResult.Rejected(
                reason = rejectionReason
            )
        }

        return HybridBootstrapCommandExecutionResult.Accepted(
            peerId = command.peerId,
            sessionId = command.sessionId,
            bootstrapIdentifier = command.bootstrapIdentifier,
            groupOwnerAddress = command.groupOwnerAddress,
            socketPort = command.socketPort,
            commandCreatedAtMillis = command.commandCreatedAtMillis
        )
    }
}
