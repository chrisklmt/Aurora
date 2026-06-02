package gr.hua.aurora.identity

import gr.hua.aurora.crypto.Sec1PublicKeyEncoding

object AndroidKeystoreLocalAgreementPublicKey {
    fun ensureAgreementPublicKeyBytes(
        identity: LocalKeyIdentity = LocalKeyIdentity.default()
    ): ByteArray {
        return Sec1PublicKeyEncoding.encodeUncompressed(
            AndroidKeystoreLocalAgreementKey.ensureAgreementKey(identity)
        )
    }

    fun loadAgreementPublicKeyBytesOrNull(
        identity: LocalKeyIdentity = LocalKeyIdentity.default()
    ): ByteArray? {
        val publicKey = AndroidKeystoreLocalAgreementKey.loadAgreementPublicKeyOrNull(identity)
            ?: return null

        return Sec1PublicKeyEncoding.encodeUncompressed(publicKey)
    }
}
