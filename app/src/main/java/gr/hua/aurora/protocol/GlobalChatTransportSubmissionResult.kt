package gr.hua.aurora.protocol

sealed interface GlobalChatTransportSubmissionResult {
    data object SubmittedLocally : GlobalChatTransportSubmissionResult
    data object SenderUnavailable : GlobalChatTransportSubmissionResult
    data object NoSecurePeerSelected : GlobalChatTransportSubmissionResult
    data object SessionMaterialUnavailable : GlobalChatTransportSubmissionResult
    data class Failed(
        val reason: String
    ) : GlobalChatTransportSubmissionResult {
        init {
            require(reason.isNotBlank()) {
                "Global chat transport submission failure reason must not be blank."
            }
        }
    }
}
