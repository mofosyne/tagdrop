package com.github.mofosyne.tagdrop.data.signing

import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

private const val FORMAT = "tagdrop-signing-identity"
private const val VERSION = 1
private const val KDF_ITERS = 100000
private const val KDF_SALT_BYTES = 16
private const val GCM_NONCE_BYTES = 12

/**
 * Serializes [identity] into a passphrase-protected backup file — same JSON shape as the web
 * generator's `exportSigningIdentity()` (same field names/hex encoding), so a backup made in
 * either implementation can in principle be read by the other. Only `secretKey` is encrypted
 * (PBKDF2 + AES-256-GCM, reusing [TagDropCodec]'s SPEC §9 primitives); `publicKey`/`signerId`/
 * `label` are stored in the clear since they aren't secret — a signer_pubkey/signer_id pair is
 * already visible in every signed code.
 */
fun exportSigningIdentity(identity: SigningIdentity, passphrase: String): ByteArray {
    val kdfSalt = ByteArray(KDF_SALT_BYTES).also { SecureRandom().nextBytes(it) }
    val key = TagDropCodec.deriveKeyFromPassphrase(passphrase, kdfSalt, KDF_ITERS)
    val nonce = TagDropCodec.generateNonce()
    val encryptedSecretKey = nonce + TagDropCodec.encryptAesGcm(identity.secretKey, key, nonce)

    val json = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("publicKey", identity.publicKey.toHex())
        .put("signerId", identity.signerId.toHex())
        .put("kdfAlg", 1)
        .put("kdfSalt", kdfSalt.toHex())
        .put("kdfIters", KDF_ITERS)
        .put("encryptedSecretKey", encryptedSecretKey.toHex())
    identity.label?.let { json.put("label", it) }
    return json.toString(2).toByteArray(Charsets.UTF_8)
}

/** Outcome of [importSigningIdentity]. */
sealed class SigningIdentityImport {
    data class Ok(val identity: SigningIdentity) : SigningIdentityImport()
    /** Not a recognizable backup file at all — bad JSON, wrong `format`/`kdfAlg`, or a missing/malformed field. */
    object Malformed : SigningIdentityImport()
    /** Recognizable, but the passphrase didn't decrypt `encryptedSecretKey` (wrong passphrase, or the file was tampered/corrupted). */
    object WrongPassphrase : SigningIdentityImport()
    /** Decrypted fine, but `signerId` doesn't match `sha256(publicKey)[0:8]` — internally inconsistent, e.g. a hand-edited file. */
    object Inconsistent : SigningIdentityImport()
}

/** Reverses [exportSigningIdentity]: decrypts [jsonBytes] with [passphrase] and validates the result before returning it. */
fun importSigningIdentity(jsonBytes: ByteArray, passphrase: String): SigningIdentityImport {
    val json = runCatching { JSONObject(String(jsonBytes, Charsets.UTF_8)) }.getOrNull()
        ?: return SigningIdentityImport.Malformed
    if (json.optString("format") != FORMAT || json.optInt("kdfAlg") != 1) return SigningIdentityImport.Malformed

    val kdfSalt = json.optString("kdfSalt").hexToBytesOrNull() ?: return SigningIdentityImport.Malformed
    val kdfIters = json.optInt("kdfIters", 0).takeIf { it > 0 } ?: return SigningIdentityImport.Malformed
    val encryptedSecretKey = json.optString("encryptedSecretKey").hexToBytesOrNull()
        ?.takeIf { it.size > GCM_NONCE_BYTES } ?: return SigningIdentityImport.Malformed
    val publicKey = json.optString("publicKey").hexToBytesOrNull() ?: return SigningIdentityImport.Malformed
    val signerId = json.optString("signerId").hexToBytesOrNull() ?: return SigningIdentityImport.Malformed

    val key = TagDropCodec.deriveKeyFromPassphrase(passphrase, kdfSalt, kdfIters)
    val nonce = encryptedSecretKey.copyOfRange(0, GCM_NONCE_BYTES)
    val ciphertextAndTag = encryptedSecretKey.copyOfRange(GCM_NONCE_BYTES, encryptedSecretKey.size)
    val secretKey = TagDropCodec.decryptAesGcm(ciphertextAndTag, key, nonce) ?: return SigningIdentityImport.WrongPassphrase

    val expectedSignerId = MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(8)
    if (!expectedSignerId.contentEquals(signerId)) return SigningIdentityImport.Inconsistent

    val label = json.opt("label") as? String
    return SigningIdentityImport.Ok(SigningIdentity(secretKey, publicKey, signerId, label))
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
private fun String.hexToBytesOrNull(): ByteArray? {
    if (isEmpty() || length % 2 != 0) return null
    return runCatching {
        ByteArray(length / 2) { i -> ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte() }
    }.getOrNull()
}
