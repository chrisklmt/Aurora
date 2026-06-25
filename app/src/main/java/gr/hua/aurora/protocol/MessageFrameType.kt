package gr.hua.aurora.protocol

// Οι τύποι frame περιγράφουν κατηγορίες envelope σε επίπεδο εφαρμογής και όχι συγκεκριμένο transport.
enum class MessageFrameType {
    GLOBAL_TEXT,
    PRIVATE_TEXT,
    IDENTITY_EXCHANGE,
    CONTROL
}
