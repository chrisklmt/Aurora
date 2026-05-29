package gr.hua.aurora.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

object Sec1PublicKeyEncoding {
    private const val curveName = "secp256r1"
    private const val fieldSizeBytes = 32
    private const val encodedLengthBytes = 65
    private const val uncompressedPrefix: Byte = 0x04

    // Ο helper ορίζει μόνο boundary μετατροπής P-256 public keys σε bytes και δεν προσθέτει exchange ή transport semantics.
    fun encodeUncompressed(publicKey: ECPublicKey): ByteArray {
        requireExpectedCurve(publicKey.params)

        val point = publicKey.w
        val xBytes = toFixedLengthUnsigned(point.affineX, fieldSizeBytes)
        val yBytes = toFixedLengthUnsigned(point.affineY, fieldSizeBytes)
        return byteArrayOf(uncompressedPrefix) + xBytes + yBytes
    }

    fun decodeUncompressed(encoded: ByteArray): ECPublicKey {
        require(encoded.size == encodedLengthBytes) {
            "SEC1 uncompressed public key must be $encodedLengthBytes bytes."
        }
        require(encoded[0] == uncompressedPrefix) {
            "SEC1 uncompressed public key must start with 0x04."
        }

        val x = BigInteger(1, encoded.copyOfRange(1, 33))
        val y = BigInteger(1, encoded.copyOfRange(33, 65))
        val params = expectedP256Params()
        require(isPointOnCurve(x, y, params)) {
            "SEC1 public key point must be valid on the $curveName curve."
        }
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(x, y), params)
        )

        require(publicKey is ECPublicKey) { "Decoded key must be an ECPublicKey." }
        requireExpectedCurve(publicKey.params)
        return publicKey
    }

    private fun requireExpectedCurve(params: ECParameterSpec?) {
        require(params != null && isExpectedP256(params)) {
            "Public key must use the $curveName curve."
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

    private fun isPointOnCurve(
        x: BigInteger,
        y: BigInteger,
        params: ECParameterSpec
    ): Boolean {
        val field = params.curve.field as? ECFieldFp ?: return false
        val p = field.p

        if (x < BigInteger.ZERO || y < BigInteger.ZERO) return false
        if (x >= p || y >= p) return false

        val left = y.modPow(BigInteger.TWO, p)
        val right = x.modPow(BigInteger.valueOf(3), p)
            .add(params.curve.a.multiply(x))
            .add(params.curve.b)
            .mod(p)

        return left == right
    }

    private fun toFixedLengthUnsigned(
        value: BigInteger,
        size: Int
    ): ByteArray {
        val src = value.toByteArray()
        return when {
            src.size == size -> src
            src.size < size -> ByteArray(size - src.size) + src
            src.size == size + 1 && src[0] == 0.toByte() -> src.copyOfRange(1, src.size)
            else -> throw IllegalArgumentException("Coordinate does not fit in $size bytes.")
        }
    }
}
