package gr.hua.aurora.model

// Το ContactPreview περιγράφει μόνο συνοπτική εικόνα επαφής για το app/UI layer στο παρόν στάδιο.
data class ContactPreview(
    val id: String,
    val displayName: String,
    val detail: String,
    val lastSeenAtMillis: Long? = null,
    val preferredTransport: TransportType = TransportType.UNKNOWN,
    val isTrusted: Boolean = false
)

// Η σημαία trust είναι προσωρινή ένδειξη UI και δεν πρέπει να θεωρείται επαληθευμένη ταυτότητα.
