package gr.hua.aurora.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

object AndroidKeystoreLocalAgreementKey {
    private const val provider = "AndroidKeyStore"
    private const val curveName = "secp256r1"

    fun ensureAgreementKey(
        identity: LocalKeyIdentity = LocalKeyIdentity.default()
    ): ECPublicKey {
        loadAgreementPublicKeyOrNull(identity)?.let { return it }

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            provider
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                identity.keyAgreementAlias,
                KeyProperties.PURPOSE_AGREE_KEY
            ).setAlgorithmParameterSpec(ECGenParameterSpec(curveName))
                .build()
        )
        generator.generateKeyPair()

        return requireNotNull(loadAgreementPublicKeyOrNull(identity)) {
            "Android Keystore agreement key must be available after generation."
        }
    }

    fun loadAgreementPublicKeyOrNull(
        identity: LocalKeyIdentity = LocalKeyIdentity.default()
    ): ECPublicKey? {
        val keyStore = KeyStore.getInstance(provider).apply {
            load(null)
        }
        val certificate = keyStore.getCertificate(identity.keyAgreementAlias) ?: return null

        return certificate.publicKey as? ECPublicKey
    }
}
