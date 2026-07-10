package gr.hua.aurora.transport.hybrid

fun interface HybridBootstrapSocketDialer {
    fun dial(
        address: String,
        port: Int,
        connectTimeoutMillis: Long
    ): HybridBootstrapSocketDialResult
}
