package gr.hua.aurora.model

// Το status χρησιμοποιείται μόνο για τοπική απεικόνιση προόδου στο UI και όχι ως απόδειξη παράδοσης.
enum class MessageStatus {
    DRAFT,
    RECEIVED,
    QUEUED,
    LOCAL_ONLY,
    SENT,
    DELIVERED,
    FAILED
}
