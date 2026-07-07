package gr.hua.aurora.transport.hybrid

internal interface HybridTransportControlStore {
    fun record(
        peerId: String,
        message: HybridTransportControlMessage
    ): RecordResult

    fun snapshot(): HybridTransportControlState

    fun clear()

    sealed interface RecordResult {
        data object Stored : RecordResult

        data object IgnoredOlderMessage : RecordResult

        data object IgnoredInvalidPeerId : RecordResult
    }
}
