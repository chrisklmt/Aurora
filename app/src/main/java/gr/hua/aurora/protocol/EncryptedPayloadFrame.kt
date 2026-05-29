package gr.hua.aurora.protocol

private const val supportedEncryptedPayloadProtocolVersion = 1
private const val encryptedPayloadNonceLengthBytes = 12

class EncryptedPayloadFrame(
    val protocolVersion: Int = supportedEncryptedPayloadProtocolVersion,
    nonce: ByteArray,
    ciphertext: ByteArray
) {
    private val storedNonce = nonce.copyOf()
    private val storedCiphertext = ciphertext.copyOf()

    init {
        require(protocolVersion == supportedEncryptedPayloadProtocolVersion) {
            "Encrypted payload protocolVersion must be $supportedEncryptedPayloadProtocolVersion."
        }
        require(storedNonce.size == encryptedPayloadNonceLengthBytes) {
            "Encrypted payload nonce must be $encryptedPayloadNonceLengthBytes bytes."
        }
        require(storedCiphertext.isNotEmpty()) {
            "Encrypted payload ciphertext must not be empty."
        }
    }

    val nonce: ByteArray
        get() = storedNonce.copyOf()

    val ciphertext: ByteArray
        get() = storedCiphertext.copyOf()
}
