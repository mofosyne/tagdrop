package com.github.mofosyne.tagdrop.data.signing

import com.github.mofosyne.tagdrop.data.db.SignatureStatus
import com.github.mofosyne.tagdrop.data.db.SignerDao
import com.github.mofosyne.tagdrop.data.db.TrustedSigner
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.data.format.TagDropPayload
import java.security.MessageDigest

/** Result of verifying a Content/Paper's Verified Authorship fields (SPEC §10). */
data class SignatureVerification(
    val status: Int,           // SignatureStatus.*
    val signerIdHex: String?,  // null only when status == NONE
    val signerLabel: String?
)

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

/**
 * Verifies a decoded Content/Paper's Verified Authorship fields (SPEC §10) — mirrors the web
 * reader's `verifySignatureCommon()`. [computeHash] supplies the format-specific signed-message
 * hash (`TagDropCodec.contentSignedMessageHash`/`paperSignedMessageHash`, over the LOGICAL
 * `previewRaw`/`bodyRaw` — not reconstructable from wire-form code bytes alone once
 * Split/Compress-wrapped); everything else (TOFU key caching, signer_id binding, the
 * ML-DSA-44 check itself) is format-independent and shared here. TOFU: an embedded
 * [signerPubkey] is cached in [signerDao] under [signerId] the first time it's seen, so later
 * codes from the same signer verify even when they omit it to save space (SPEC §10 "Key
 * caching").
 */
suspend fun verifySignatureCommon(
    signatureAlgorithm: Int,
    signature: ByteArray?,
    signerPubkey: ByteArray?,
    signerId: ByteArray?,
    signerLabel: String?,
    signerDao: SignerDao,
    computeHash: () -> ByteArray
): SignatureVerification {
    // Unsigned, or an algorithm this app doesn't recognise (SPEC §3 forward-compat: ignore).
    if (signatureAlgorithm != TagDropCodec.SIGNATURE_ALG_MLDSA44) return SignatureVerification(SignatureStatus.NONE, null, null)
    val signerIdHex = signerId?.toHex()
    var pubkey = signerPubkey
    if (pubkey != null && signerIdHex != null) {
        // TOFU only holds if signer_id actually is sha256(signer_pubkey)[0:8] (SPEC §3) — an
        // attacker who has merely SEEN someone's signer_id (visible on every one of their signed
        // codes) could otherwise mint a new code under that same signer_id with their OWN
        // pubkey/signature, and this cache would blindly overwrite the real signer's trusted key
        // with the attacker's, both trusting the forgery and poisoning future genuine codes.
        val expectedSignerId = MessageDigest.getInstance("SHA-256").digest(pubkey).copyOf(8)
        if (!expectedSignerId.contentEquals(signerId)) return SignatureVerification(SignatureStatus.INVALID, signerIdHex, signerLabel)
        val existing = signerDao.getBySignerId(signerIdHex)
        signerDao.insert(TrustedSigner(signerIdHex, pubkey, signerLabel ?: existing?.label, System.currentTimeMillis()))
    } else if (signerIdHex != null) {
        pubkey = signerDao.getBySignerId(signerIdHex)?.publicKey
    }
    if (pubkey == null || signature == null) return SignatureVerification(SignatureStatus.PENDING, signerIdHex, signerLabel)
    val hash = computeHash()
    val ok = MLDSA44.verify(signature, hash, pubkey)
    val cachedLabel = signerIdHex?.let { signerDao.getBySignerId(it)?.label }
    return SignatureVerification(
        if (ok) SignatureStatus.VERIFIED else SignatureStatus.INVALID,
        signerIdHex,
        signerLabel ?: cachedLabel
    )
}

/**
 * Verifies a scanned Content's Verified Authorship fields against its LOGICAL [previewRaw]/
 * [bodyRaw] (SPEC §10) — [bodyRaw] null for a key-only code (Preview only, no Body at all;
 * [TagDropCodec.contentSignedMessageHash] treats that as an empty-bytes contribution, same as
 * the web reader).
 */
suspend fun verifyContentSignature(previewRaw: ByteArray, bodyRaw: ByteArray?, content: TagDropPayload.Content, signerDao: SignerDao): SignatureVerification =
    verifySignatureCommon(
        content.signatureAlgorithm, content.signature, content.signerPubkey, content.signerId, content.signerLabel, signerDao
    ) { TagDropCodec.contentSignedMessageHash(previewRaw, bodyRaw) }

/** Verifies a scanned Paper's Verified Authorship fields against its LOGICAL [previewRaw]/[bodyRaw] (SPEC §10). */
suspend fun verifyPaperSignature(previewRaw: ByteArray, bodyRaw: ByteArray, paper: TagDropPayload.Paper, signerDao: SignerDao): SignatureVerification =
    verifySignatureCommon(
        paper.signatureAlgorithm, paper.signature, paper.signerPubkey, paper.signerId, paper.signerLabel, signerDao
    ) { TagDropCodec.paperSignedMessageHash(previewRaw, bodyRaw) }
