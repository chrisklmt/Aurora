package gr.hua.aurora.crypto

import java.security.interfaces.ECPrivateKey

object PeerSessionKeyAgreement {
    fun deriveSessionKey(
        privateKey: ECPrivateKey,
        peerPublicKeyBytes: ByteArray
    ): ByteArray {
        val peerPublicKey = Sec1PublicKeyEncoding.decodeUncompressed(peerPublicKeyBytes)
        val sharedSecret = EcdhKeyAgreement.deriveSharedSecret(
            privateKey = privateKey,
            publicKey = peerPublicKey
        )
        return HkdfSessionKeyDerivation.deriveSessionKey(sharedSecret)
    }
}
