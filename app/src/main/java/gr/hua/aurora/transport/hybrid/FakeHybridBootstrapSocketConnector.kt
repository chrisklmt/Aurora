package gr.hua.aurora.transport.hybrid

class FakeHybridBootstrapSocketConnector(
    private val resultProvider: (HybridBootstrapSocketExecutionPlan) -> HybridBootstrapSocketConnectionResult
) : HybridBootstrapSocketConnector {
    private val recordedPlans = mutableListOf<HybridBootstrapSocketExecutionPlan>()

    val connectedPlans: List<HybridBootstrapSocketExecutionPlan>
        get() = recordedPlans.toList()

    override fun connect(
        plan: HybridBootstrapSocketExecutionPlan
    ): HybridBootstrapSocketConnectionResult {
        recordedPlans += plan
        return resultProvider(plan)
    }

    companion object {
        fun connected(
            connectedAtMillis: Long
        ): FakeHybridBootstrapSocketConnector {
            return FakeHybridBootstrapSocketConnector { plan ->
                HybridBootstrapSocketConnectionResult.Connected(
                    peerId = plan.peerId,
                    sessionId = plan.sessionId,
                    bootstrapIdentifier = plan.bootstrapIdentifier,
                    groupOwnerAddress = plan.groupOwnerAddress,
                    socketPort = plan.socketPort,
                    connectedAtMillis = connectedAtMillis
                )
            }
        }

        fun failed(
            reason: String
        ): FakeHybridBootstrapSocketConnector {
            return FakeHybridBootstrapSocketConnector {
                HybridBootstrapSocketConnectionResult.Failed(
                    reason = reason
                )
            }
        }
    }
}
