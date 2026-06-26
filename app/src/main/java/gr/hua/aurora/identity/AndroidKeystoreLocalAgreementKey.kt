package gr.hua.aurora.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

object AndroidKeystoreLocalAgreementKey {
    private const val provider = "AndroidKeyStore"
    private const val curveName = "secp256r1"

    data class LocalIdentityClearResult(
        val clearedAliases: Set<String>
    )

    sealed interface PrivateKeyLoadResult {
        data class Ready(
            val privateKey: PrivateKey,
            val wasGenerated: Boolean
        ) : PrivateKeyLoadResult

        data class KeystoreUnavailable(
            val reason: String
        ) : PrivateKeyLoadResult {
            init {
                require(reason.isNotBlank()) {
                    "Keystore unavailable reason must not be blank."
                }
            }
        }

        data class GenerationFailed(
            val reason: String
        ) : PrivateKeyLoadResult {
            init {
                require(reason.isNotBlank()) {
                    "Generation failed reason must not be blank."
                }
            }
        }

        data class LoadFailed(
            val reason: String
        ) : PrivateKeyLoadResult {
            init {
                require(reason.isNotBlank()) {
                    "Load failed reason must not be blank."
                }
            }
        }

        data class InvalidExistingKey(
            val reason: String
        ) : PrivateKeyLoadResult {
            init {
                require(reason.isNotBlank()) {
                    "Invalid existing key reason must not be blank."
                }
            }
        }

        data class RegeneratedAfterInvalidExistingKey(
            val privateKey: PrivateKey
        ) : PrivateKeyLoadResult
    }

    private sealed interface ExistingAgreementKeyState {
        data object Missing : ExistingAgreementKeyState

        data class Valid(
            val publicKey: ECPublicKey,
            val privateKey: PrivateKey
        ) : ExistingAgreementKeyState

        data class Invalid(
            val reason: String
        ) : ExistingAgreementKeyState {
            init {
                require(reason.isNotBlank()) {
                    "Existing agreement key invalid reason must not be blank."
                }
            }
        }
    }

    fun ensureAgreementKey(
        identity: LocalKeyIdentity = LocalKeyIdentity.default()
    ): ECPublicKey {
        val keyStore = loadKeyStoreOrThrow()

        return when (val state = inspectExistingAgreementKey(keyStore, identity)) {
            is ExistingAgreementKeyState.Valid -> state.publicKey
            ExistingAgreementKeyState.Missing -> {
                generateAgreementKeyOrThrow(identity)
                requireNotNull(loadAgreementPublicKeyOrNull(identity)) {
                    "Android Keystore agreement key must be available after generation."
                }
            }
            is ExistingAgreementKeyState.Invalid -> {
                keyStore.deleteEntry(identity.keyAgreementAlias)
                generateAgreementKeyOrThrow(identity)
                requireNotNull(loadAgreementPublicKeyOrNull(identity)) {
                    "Android Keystore agreement key must be available after regeneration."
                }
            }
        }
    }

    fun loadAgreementPublicKeyOrNull(
        identity: LocalKeyIdentity = LocalKeyIdentity.default()
    ): ECPublicKey? {
        val keyStore = loadKeyStoreOrNull() ?: return null
        val certificate = keyStore.getCertificate(identity.keyAgreementAlias) ?: return null

        return certificate.publicKey as? ECPublicKey
    }

    fun loadAgreementPrivateKeyOrNull(
        identity: LocalKeyIdentity = LocalKeyIdentity.default()
    ): PrivateKey? {
        val keyStore = loadKeyStoreOrNull() ?: return null

        return when (val state = inspectExistingAgreementKey(keyStore, identity)) {
            is ExistingAgreementKeyState.Valid -> state.privateKey
            ExistingAgreementKeyState.Missing,
            is ExistingAgreementKeyState.Invalid -> null
        }
    }

    fun ensureAgreementPrivateKey(
        identity: LocalKeyIdentity = LocalKeyIdentity.default()
    ): PrivateKeyLoadResult {
        val keyStore = loadKeyStoreOrResult()
        if (keyStore is KeyStoreLoadResult.Unavailable) {
            return PrivateKeyLoadResult.KeystoreUnavailable(keyStore.reason)
        }
        val loadedKeyStore = keyStore as KeyStoreLoadResult.Loaded

        return when (val state = inspectExistingAgreementKey(loadedKeyStore.keyStore, identity)) {
            is ExistingAgreementKeyState.Valid -> {
                PrivateKeyLoadResult.Ready(
                    privateKey = state.privateKey,
                    wasGenerated = false
                )
            }
            ExistingAgreementKeyState.Missing -> {
                generateAndLoadAgreementPrivateKey(
                    identity = identity,
                    wasRegeneration = false
                )
            }
            is ExistingAgreementKeyState.Invalid -> {
                val didDeleteInvalidKey = runCatching {
                    loadedKeyStore.keyStore.deleteEntry(identity.keyAgreementAlias)
                }.isSuccess
                if (!didDeleteInvalidKey) {
                    return PrivateKeyLoadResult.InvalidExistingKey(
                        reason = state.reason
                    )
                }

                when (
                    val regenerated = generateAndLoadAgreementPrivateKey(
                        identity = identity,
                        wasRegeneration = true
                    )
                ) {
                    is PrivateKeyLoadResult.Ready -> {
                        PrivateKeyLoadResult.RegeneratedAfterInvalidExistingKey(
                            privateKey = regenerated.privateKey
                        )
                    }
                    is PrivateKeyLoadResult.RegeneratedAfterInvalidExistingKey,
                    is PrivateKeyLoadResult.KeystoreUnavailable,
                    is PrivateKeyLoadResult.GenerationFailed,
                    is PrivateKeyLoadResult.LoadFailed,
                    is PrivateKeyLoadResult.InvalidExistingKey -> regenerated
                }
            }
        }
    }

    fun ensureAgreementPrivateKeyOrNull(
        identity: LocalKeyIdentity = LocalKeyIdentity.default()
    ): PrivateKey? {
        return when (val result = ensureAgreementPrivateKey(identity)) {
            is PrivateKeyLoadResult.Ready -> result.privateKey
            is PrivateKeyLoadResult.RegeneratedAfterInvalidExistingKey -> result.privateKey
            is PrivateKeyLoadResult.KeystoreUnavailable,
            is PrivateKeyLoadResult.GenerationFailed,
            is PrivateKeyLoadResult.LoadFailed,
            is PrivateKeyLoadResult.InvalidExistingKey -> null
        }
    }

    fun clearLocalIdentityEntries(
        identity: LocalKeyIdentity = LocalKeyIdentity.default()
    ): LocalIdentityClearResult {
        val keyStore = loadKeyStoreOrThrow()
        return clearLocalIdentityEntries(
            identity = identity,
            hasAlias = keyStore::containsAlias,
            deleteAlias = { alias ->
                keyStore.deleteEntry(alias)
            }
        )
    }

    private fun loadKeyStoreOrThrow(): KeyStore {
        return KeyStore.getInstance(provider).apply {
            load(null)
        }
    }

    internal fun clearLocalIdentityEntries(
        identity: LocalKeyIdentity,
        hasAlias: (String) -> Boolean,
        deleteAlias: (String) -> Unit
    ): LocalIdentityClearResult {
        val clearedAliases = linkedSetOf<String>()
        listOf(identity.signingAlias, identity.keyAgreementAlias).forEach { alias ->
            if (!hasAlias(alias)) {
                return@forEach
            }
            deleteAlias(alias)
            clearedAliases += alias
        }

        return LocalIdentityClearResult(
            clearedAliases = clearedAliases
        )
    }

    private fun loadKeyStoreOrNull(): KeyStore? {
        return runCatching {
            loadKeyStoreOrThrow()
        }.getOrNull()
    }

    private sealed interface KeyStoreLoadResult {
        data class Loaded(
            val keyStore: KeyStore
        ) : KeyStoreLoadResult

        data class Unavailable(
            val reason: String
        ) : KeyStoreLoadResult
    }

    private fun loadKeyStoreOrResult(): KeyStoreLoadResult {
        return runCatching {
            KeyStoreLoadResult.Loaded(loadKeyStoreOrThrow())
        }.getOrElse { error ->
            KeyStoreLoadResult.Unavailable(
                reason = safeReason(
                    prefix = "Android Keystore unavailable",
                    error = error
                )
            )
        }
    }

    private fun inspectExistingAgreementKey(
        keyStore: KeyStore,
        identity: LocalKeyIdentity
    ): ExistingAgreementKeyState {
        val certificate = runCatching {
            keyStore.getCertificate(identity.keyAgreementAlias)
        }.getOrNull()
        val privateKey = runCatching {
            keyStore.getKey(identity.keyAgreementAlias, null) as? PrivateKey
        }.getOrElse { error ->
            return ExistingAgreementKeyState.Invalid(
                reason = safeReason(
                    prefix = "Stored agreement key could not be loaded",
                    error = error
                )
            )
        }

        if (certificate == null && privateKey == null) {
            return ExistingAgreementKeyState.Missing
        }

        val publicKey = certificate?.publicKey as? ECPublicKey
            ?: return ExistingAgreementKeyState.Invalid(
                reason = "Stored agreement public key is incompatible."
            )
        if (!keyStore.isKeyEntry(identity.keyAgreementAlias)) {
            return ExistingAgreementKeyState.Invalid(
                reason = "Stored agreement private key entry is incompatible."
            )
        }
        privateKey ?: return ExistingAgreementKeyState.Invalid(
            reason = "Stored agreement private key is missing."
        )
        if (!privateKey.algorithm.equals(KeyProperties.KEY_ALGORITHM_EC, ignoreCase = true)) {
            return ExistingAgreementKeyState.Invalid(
                reason = "Stored agreement private key is incompatible."
            )
        }

        return ExistingAgreementKeyState.Valid(
            publicKey = publicKey,
            privateKey = privateKey
        )
    }

    private fun generateAndLoadAgreementPrivateKey(
        identity: LocalKeyIdentity,
        wasRegeneration: Boolean
    ): PrivateKeyLoadResult {
        val didGenerate = runCatching {
            generateAgreementKeyOrThrow(identity)
        }.getOrElse { error ->
            return PrivateKeyLoadResult.GenerationFailed(
                reason = safeReason(
                    prefix = "Agreement private key generation failed",
                    error = error
                )
            )
        }

        if (didGenerate == Unit) {
            val keyStore = loadKeyStoreOrResult()
            if (keyStore is KeyStoreLoadResult.Unavailable) {
                return PrivateKeyLoadResult.KeystoreUnavailable(keyStore.reason)
            }
            val loadedKeyStore = keyStore as KeyStoreLoadResult.Loaded

            return when (val state = inspectExistingAgreementKey(loadedKeyStore.keyStore, identity)) {
                is ExistingAgreementKeyState.Valid -> {
                    PrivateKeyLoadResult.Ready(
                        privateKey = state.privateKey,
                        wasGenerated = !wasRegeneration
                    )
                }
                ExistingAgreementKeyState.Missing -> {
                    PrivateKeyLoadResult.LoadFailed(
                        reason = "Agreement private key could not be loaded after generation."
                    )
                }
                is ExistingAgreementKeyState.Invalid -> {
                    if (wasRegeneration) {
                        PrivateKeyLoadResult.InvalidExistingKey(state.reason)
                    } else {
                        PrivateKeyLoadResult.LoadFailed(state.reason)
                    }
                }
            }
        }

        return PrivateKeyLoadResult.LoadFailed(
            reason = "Agreement private key could not be loaded after generation."
        )
    }

    private fun generateAgreementKeyOrThrow(
        identity: LocalKeyIdentity
    ) {
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
    }

    private fun safeReason(
        prefix: String,
        error: Throwable
    ): String {
        return "$prefix (${error::class.java.simpleName})"
    }
}
