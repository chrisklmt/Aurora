package gr.hua.aurora.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedPayload(
    nonce: ByteArray,
    ciphertext: ByteArray
) {
    private val storedNonce = nonce.copyOf()
    private val storedCiphertext = ciphertext.copyOf()

    init {
        require(storedNonce.size == nonceLengthBytes) {
            "EncryptedPayload nonce must be $nonceLengthBytes bytes."
        }
        require(storedCiphertext.size >= authTagLengthBytes) {
            "EncryptedPayload ciphertext must include at least the GCM tag bytes."
        }
    }

    val nonce: ByteArray
        get() = storedNonce.copyOf()

    val ciphertext: ByteArray
        get() = storedCiphertext.copyOf()
}

object AesGcmCipher {
    private const val transformation = "AES/GCM/NoPadding"
    private const val algorithm = "AES"
    private const val acceptedKeyLengthBytes = 32
    private val secureRandom = SecureRandom()

    // Ο helper προστατεύει μόνο bytes payload όταν χρησιμοποιείται σωστά και δεν καλύπτει
    // ταυτότητα, ανταλλαγή κλειδιών, αποθήκευση κλειδιών, επαναλήψεις ή ασφάλεια καναλιού από μόνος του.
    fun encrypt(keyBytes: ByteArray, plaintext: ByteArray): EncryptedPayload {
        return encrypt(
            keyBytes = keyBytes,
            plaintext = plaintext,
            authenticatedData = null
        )
    }

    fun encrypt(
        keyBytes: ByteArray,
        plaintext: ByteArray,
        authenticatedData: ByteArray?
    ): EncryptedPayload {
        requireKeyLength(keyBytes)

        val nonce = ByteArray(nonceLengthBytes)
        secureRandom.nextBytes(nonce)

        val cipher = Cipher.getInstance(transformation)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes.copyOf(), algorithm),
            GCMParameterSpec(authTagLengthBits, nonce)
        )
        updateAuthenticatedData(cipher, authenticatedData)

        return EncryptedPayload(
            nonce = nonce,
            ciphertext = cipher.doFinal(plaintext.copyOf())
        )
    }

    fun decrypt(keyBytes: ByteArray, encryptedPayload: EncryptedPayload): ByteArray {
        return decrypt(
            keyBytes = keyBytes,
            encryptedPayload = encryptedPayload,
            authenticatedData = null
        )
    }

    fun decrypt(
        keyBytes: ByteArray,
        encryptedPayload: EncryptedPayload,
        authenticatedData: ByteArray?
    ): ByteArray {
        requireKeyLength(keyBytes)

        val cipher = Cipher.getInstance(transformation)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes.copyOf(), algorithm),
            GCMParameterSpec(authTagLengthBits, encryptedPayload.nonce)
        )
        updateAuthenticatedData(cipher, authenticatedData)

        return cipher.doFinal(encryptedPayload.ciphertext)
    }

    private fun requireKeyLength(keyBytes: ByteArray) {
        require(keyBytes.size == acceptedKeyLengthBytes) {
            "AES-GCM helper accepts only $acceptedKeyLengthBytes-byte keys."
        }
    }

    private fun updateAuthenticatedData(
        cipher: Cipher,
        authenticatedData: ByteArray?
    ) {
        authenticatedData
            ?.takeIf { it.isNotEmpty() }
            ?.let { cipher.updateAAD(it.copyOf()) }
    }
}

private const val nonceLengthBytes = 12
private const val authTagLengthBits = 128
private const val authTagLengthBytes = authTagLengthBits / 8
