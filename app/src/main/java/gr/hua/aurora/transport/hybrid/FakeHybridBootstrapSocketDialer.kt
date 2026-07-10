package gr.hua.aurora.transport.hybrid

class FakeHybridBootstrapSocketDialer(
    private val resultProvider: (DialRequest) -> HybridBootstrapSocketDialResult
) : HybridBootstrapSocketDialer {
    data class DialRequest(
        val address: String,
        val port: Int,
        val connectTimeoutMillis: Long
    )

    private val recordedRequests = mutableListOf<DialRequest>()

    val dialRequests: List<DialRequest>
        get() = recordedRequests.toList()

    override fun dial(
        address: String,
        port: Int,
        connectTimeoutMillis: Long
    ): HybridBootstrapSocketDialResult {
        val request = DialRequest(
            address = address,
            port = port,
            connectTimeoutMillis = connectTimeoutMillis
        )
        recordedRequests += request
        return resultProvider(request)
    }

    companion object {
        fun connected(
            connectedAtMillis: Long
        ): FakeHybridBootstrapSocketDialer {
            return FakeHybridBootstrapSocketDialer { request ->
                HybridBootstrapSocketDialResult.Connected(
                    address = request.address,
                    port = request.port,
                    connectedAtMillis = connectedAtMillis
                )
            }
        }

        fun failed(
            reason: String
        ): FakeHybridBootstrapSocketDialer {
            return FakeHybridBootstrapSocketDialer {
                HybridBootstrapSocketDialResult.Failed(
                    reason = reason
                )
            }
        }
    }
}
