package gr.hua.aurora.crypto

import java.security.AlgorithmParameters
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import javax.crypto.KeyAgreement

object EcdhKeyAgreement {
    private const val algorithm = "ECDH"
    private const val curveName = "secp256r1"

    fun deriveSharedSecret(
        privateKey: ECPrivateKey,
        publicKey: ECPublicKey
    ): ByteArray {
        requireExpectedCurve(privateKey.params, "privateKey")
        requireExpectedCurve(publicKey.params, "publicKey")

        val keyAgreement = KeyAgreement.getInstance(algorithm)
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)
        return keyAgreement.generateSecret()
    }

    private fun requireExpectedCurve(
        params: ECParameterSpec?,
        keyLabel: String
    ) {
        require(params != null && isExpectedP256(params)) {
            "$keyLabel must use the $curveName curve."
        }
    }

    private fun isExpectedP256(params: ECParameterSpec): Boolean {
        val expected = expectedP256Params()
        val field = params.curve.field
        val expectedField = expected.curve.field

        return field is ECFieldFp &&
            expectedField is ECFieldFp &&
            field.p == expectedField.p &&
            params.curve.a == expected.curve.a &&
            params.curve.b == expected.curve.b &&
            params.generator.affineX == expected.generator.affineX &&
            params.generator.affineY == expected.generator.affineY &&
            params.order == expected.order &&
            params.cofactor == expected.cofactor
    }

    private fun expectedP256Params(): ECParameterSpec {
        return AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(curveName))
        }.getParameterSpec(ECParameterSpec::class.java)
    }
}
