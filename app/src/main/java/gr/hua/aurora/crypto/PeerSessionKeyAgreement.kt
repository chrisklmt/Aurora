package gr.hua.aurora.crypto

import java.security.PrivateKey

object PeerSessionKeyAgreement {
    fun deriveSessionKey(
        privateKey: PrivateKey,
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
