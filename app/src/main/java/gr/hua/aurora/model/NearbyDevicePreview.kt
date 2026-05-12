package gr.hua.aurora.model

// Το NearbyDevicePreview περιγράφει μόνο in-memory preview κατάσταση για το UI και όχι πραγματική ανακάλυψη συσκευών.
data class NearbyDevicePreview(
    val id: String,
    val displayName: String,
    val detail: String,
    val transportType: TransportType = TransportType.UNKNOWN,
    val signalLabel: String? = null,
    val isConnectable: Boolean = false
)
