package com.github.mofosyne.tagdrop.data.signing

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.github.mofosyne.tagdrop.data.format.Sector
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.data.format.TagDropPayload
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * A signing identity (ML-DSA-44 keypair + label) for Verified Authorship (SPEC §10) — mirrors
 * the web generator's localStorage-persisted identity. [signerId] = SHA-256([publicKey])[0:8],
 * the same truncated-hash convention as `cache_id`/`collection_id` (SPEC §3).
 */
data class SigningIdentity(
    val secretKey: ByteArray,  // MLDSA44.SECRET_KEY_BYTES (2560) — never transmitted (SPEC §10)
    val publicKey: ByteArray,  // MLDSA44.PUBLIC_KEY_BYTES (1312)
    val signerId: ByteArray,   // 8 bytes
    val label: String?
) {
    override fun equals(other: Any?) = other is SigningIdentity && signerId.contentEquals(other.signerId)
    override fun hashCode() = signerId.contentHashCode()
}

/**
 * Loads/persists this device's signing identity in an [EncryptedSharedPreferences] file
 * (Keystore-wrapped AES — see the dependency comment in app/build.gradle for why this app's
 * usual "plain SQLite column" precedent for key material, e.g. RetainedKey's AES keys, isn't
 * good enough here: a signing key is a personal, long-lived identity, not a shared decryption
 * secret). Generated once on first use and reused after that — SPEC §10's TOFU trust model
 * depends on a *consistent* signer_id, not a fresh one per code.
 */
object SigningIdentityStore {
    private const val PREFS_FILE_NAME = "tagdrop_signing_identity"
    private const val KEY_SECRET_KEY = "secretKey"
    private const val KEY_PUBLIC_KEY = "publicKey"
    private const val KEY_SIGNER_ID = "signerId"
    private const val KEY_LABEL = "label"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context, PREFS_FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(context: Context): SigningIdentity? {
        val p = prefs(context)
        val secretKey = p.getString(KEY_SECRET_KEY, null)?.hexToBytesOrNull() ?: return null
        val publicKey = p.getString(KEY_PUBLIC_KEY, null)?.hexToBytesOrNull() ?: return null
        val signerId = p.getString(KEY_SIGNER_ID, null)?.hexToBytesOrNull() ?: return null
        return SigningIdentity(secretKey, publicKey, signerId, p.getString(KEY_LABEL, null))
    }

    fun save(context: Context, identity: SigningIdentity) {
        prefs(context).edit()
            .putString(KEY_SECRET_KEY, identity.secretKey.toHex())
            .putString(KEY_PUBLIC_KEY, identity.publicKey.toHex())
            .putString(KEY_SIGNER_ID, identity.signerId.toHex())
            .putString(KEY_LABEL, identity.label)
            .apply()
    }

    /** Returns the persisted identity, generating one on first use. [label] (if non-null) updates the stored label. */
    fun getOrCreate(context: Context, label: String? = null): SigningIdentity {
        val existing = load(context)
        val identity = when {
            existing == null -> {
                val (secretKey, publicKey) = MLDSA44.generateKeyPair()
                SigningIdentity(secretKey, publicKey, sha256(publicKey).copyOf(8), label)
            }
            label != null -> existing.copy(label = label)
            else -> existing
        }
        save(context, identity)
        return identity
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    /** Null (rather than silently-mangled bytes) on odd length or a non-hex character — a corrupted stored value should fail closed, not produce a wrong key. */
    private fun String.hexToBytesOrNull(): ByteArray? {
        if (isEmpty() || length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { i ->
                val hi = Character.digit(this[i * 2], 16)
                val lo = Character.digit(this[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return null
                ((hi shl 4) + lo).toByte()
            }
        }.getOrNull()
    }
}

private fun concatSectorBytes(sectors: List<Sector>): ByteArray {
    val out = ByteArrayOutputStream()
    for (sector in sectors) out.write(sector.sectorBytes)
    return out.toByteArray()
}

/**
 * Signs a Content payload with [identity]. [build] is a callback that builds the sectors given
 * this call's `signatureAlgorithm`/`signature`/`signerPubkey`/`signerId`/`signerLabel` args —
 * pass a lambda wrapping [TagDropCodec.createContentSectorsAutoSized] (or
 * [TagDropCodec.createContentSectors]) with the payload's other fields already bound.
 *
 * Builds with a same-length PLACEHOLDER signature first, rather than building unsigned and
 * signed versions from two independent calls. That's not just an optimization: adding ~3.7 KB
 * of signature fields can itself push a payload from single- to multi-sector, and
 * [TagDropCodec.createContentSectors] only adds `content_sha256` once it's required for a
 * multi-sector split (SPEC §3) — so an independently-built "unsigned" version can end up
 * missing a field the real signed version needs, breaking SPEC §10's "signing happens last and
 * feeds back into nothing" invariant (every other field, including content_sha256, MUST be
 * identical whether or not signing happens). Building signed-with-a-placeholder first makes
 * every sizing decision (sector count, content_sha256 inclusion) exactly what the final signed
 * build will need; [TagDropCodec.signedMessageHash] then strips the placeholder's signature
 * fields back out (same operation a verifier performs) to get the correct hash to sign.
 * Swapping the placeholder for the real signature afterward can't change any sizing decision,
 * since ML-DSA-44 signatures are fixed-length — same bytes in, same bytes out, everywhere
 * except the signature itself. Mirrors the web generator's `signSectors`.
 */
fun signContentSectors(
    identity: SigningIdentity,
    includePubkey: Boolean = true,
    build: (signatureAlgorithm: Int, signature: ByteArray?, signerPubkey: ByteArray?, signerId: ByteArray?, signerLabel: String?) -> List<Sector>
): List<Sector> {
    val pubkeyArg = if (includePubkey) identity.publicKey else null
    val placeholderSignature = ByteArray(MLDSA44.SIGNATURE_BYTES)
    val placeholderSectors = build(TagDropCodec.SIGNATURE_ALG_MLDSA44, placeholderSignature, pubkeyArg, identity.signerId, identity.label)
    val hash = TagDropCodec.signedMessageHash(concatSectorBytes(placeholderSectors))
        ?: error("signedMessageHash failed on a freshly-built stream")
    val signature = MLDSA44.sign(hash, identity.secretKey)
    return build(TagDropCodec.SIGNATURE_ALG_MLDSA44, signature, pubkeyArg, identity.signerId, identity.label)
}

/** Signs a Paper payload with [identity] — see [signContentSectors] for the placeholder-signature rationale. */
fun signPaper(
    identity: SigningIdentity,
    includePubkey: Boolean = true,
    build: (signatureAlgorithm: Int, signature: ByteArray?, signerPubkey: ByteArray?, signerId: ByteArray?, signerLabel: String?) -> Pair<TagDropPayload.Paper, List<Sector>>
): Pair<TagDropPayload.Paper, List<Sector>> {
    val pubkeyArg = if (includePubkey) identity.publicKey else null
    val placeholderSignature = ByteArray(MLDSA44.SIGNATURE_BYTES)
    val (_, placeholderSectors) = build(TagDropCodec.SIGNATURE_ALG_MLDSA44, placeholderSignature, pubkeyArg, identity.signerId, identity.label)
    val hash = TagDropCodec.signedMessageHash(concatSectorBytes(placeholderSectors))
        ?: error("signedMessageHash failed on a freshly-built stream")
    val signature = MLDSA44.sign(hash, identity.secretKey)
    return build(TagDropCodec.SIGNATURE_ALG_MLDSA44, signature, pubkeyArg, identity.signerId, identity.label)
}
