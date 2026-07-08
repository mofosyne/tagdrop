package com.github.mofosyne.tagdrop.data.signing

import java.security.SecureRandom
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner

/**
 * Raw ML-DSA-44 (Dilithium2, FIPS 204) sign/verify via BouncyCastle (SPEC §10) — this app's
 * first PQC dependency, mirroring the web tools' `@noble/post-quantum`. Operates on plain
 * byte arrays only (no key-object plumbing exposed) so callers never need BouncyCastle's own
 * types: [generateKeyPair] returns `(secretKey, publicKey)`, [sign]/[verify] take/return the
 * exact wire-format byte lengths SPEC §10 declares — no separate encode/decode step needed,
 * since BouncyCastle's `getEncoded()` for this algorithm already produces those exact lengths.
 */
object MLDSA44 {
    const val PUBLIC_KEY_BYTES = 1312
    const val SECRET_KEY_BYTES = 2560
    const val SIGNATURE_BYTES = 2420

    private val PARAMS = MLDSAParameters.ml_dsa_44

    /** Generates a fresh keypair. Returns `(secretKey, publicKey)`, [SECRET_KEY_BYTES]/[PUBLIC_KEY_BYTES] long. */
    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val generator = MLDSAKeyPairGenerator()
        generator.init(MLDSAKeyGenerationParameters(SecureRandom(), PARAMS))
        val keyPair = generator.generateKeyPair()
        val secretKey = (keyPair.private as MLDSAPrivateKeyParameters).encoded
        val publicKey = (keyPair.public as MLDSAPublicKeyParameters).encoded
        return secretKey to publicKey
    }

    /** Signs [message] (SPEC §10's signed-message hash — see TagDropCodec.signedMessageHash) with [secretKey]. */
    fun sign(message: ByteArray, secretKey: ByteArray): ByteArray {
        require(secretKey.size == SECRET_KEY_BYTES) { "ML-DSA-44 secret key must be $SECRET_KEY_BYTES bytes" }
        val privateKey = MLDSAPrivateKeyParameters(PARAMS, secretKey)
        val signer = MLDSASigner()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /**
     * Verifies [signature] over [message] against [publicKey]. Returns false (never throws) for
     * malformed input of any kind — a verifier's job is to say yes/no, not to distinguish "bad
     * signature" from "corrupt key," and SPEC §10 has no separate error-reporting channel.
     */
    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_BYTES || signature.size != SIGNATURE_BYTES) return false
        return runCatching {
            val publicKeyParams = MLDSAPublicKeyParameters(PARAMS, publicKey)
            val verifier = MLDSASigner()
            verifier.init(false, publicKeyParams)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        }.getOrDefault(false)
    }
}
