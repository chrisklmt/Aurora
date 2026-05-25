package gr.hua.aurora.identity

data class LocalKeyIdentity(
    val signingAlias: String,
    val keyAgreementAlias: String
) {
    init {
        require(signingAlias.isNotBlank()) { "signingAlias must not be blank." }
        require(keyAgreementAlias.isNotBlank()) { "keyAgreementAlias must not be blank." }
        require(signingAlias == signingAlias.trim()) { "signingAlias must be trimmed." }
        require(keyAgreementAlias == keyAgreementAlias.trim()) {
            "keyAgreementAlias must be trimmed."
        }
        require(signingAlias != keyAgreementAlias) {
            "signingAlias and keyAgreementAlias must be distinct."
        }
    }

    companion object {
        const val DEFAULT_SIGNING_ALIAS = "aurora-local-signing"
        const val DEFAULT_KEY_AGREEMENT_ALIAS = "aurora-local-agreement"

        // Το shell κρατά μόνο ονόματα ρόλων για μελλοντικά τοπικά κλειδιά και μένει ανεξάρτητο από username ή transport ταυτότητα.
        fun create(
            signingAlias: String = DEFAULT_SIGNING_ALIAS,
            keyAgreementAlias: String = DEFAULT_KEY_AGREEMENT_ALIAS
        ): LocalKeyIdentity {
            return LocalKeyIdentity(
                signingAlias = signingAlias.trim(),
                keyAgreementAlias = keyAgreementAlias.trim()
            )
        }

        fun default(): LocalKeyIdentity = create()
    }
}
