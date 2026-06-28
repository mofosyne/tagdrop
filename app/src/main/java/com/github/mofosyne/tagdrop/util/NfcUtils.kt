package com.github.mofosyne.tagdrop.util

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.github.mofosyne.tagdrop.data.format.TagDropCodec

/**
 * Builds the [NdefMessage] written to / parsed from a physical NFC tag (SPEC §12): one MIME
 * record carrying a sector's raw CBOR sequence ([TagDropCodec.sectorCbor]), optionally preceded
 * by a standard record matching the content's real type (so a phone without TagDrop installed
 * still gets something useful from the tap) and optionally followed by an Android Application
 * Record so tapping the tag launches TagDrop directly even when this app isn't already running.
 */
object NfcUtils {
    /**
     * [standardRecord], when given, becomes record 0 ahead of TagDrop's own CBOR record — Android
     * resolves `ACTION_NDEF_DISCOVERED`'s dispatch type from record 0 only, so this is what makes
     * the tag openable by a generic NFC reader. The same record-0 rule means a tag built this way
     * no longer dispatches to TagDrop itself either (SPEC §12) — there's no way to have both.
     */
    fun buildNdefMessage(
        sectorCbor: ByteArray,
        packageName: String,
        includeAppRecord: Boolean,
        standardRecord: NdefRecord? = null
    ): NdefMessage {
        val records = mutableListOf<NdefRecord>()
        if (standardRecord != null) records += standardRecord
        records += NdefRecord.createMime(TagDropCodec.NFC_MIME_TYPE, sectorCbor)
        if (includeAppRecord) records += NdefRecord.createApplicationRecord(packageName)
        return NdefMessage(records.toTypedArray())
    }

    /**
     * Builds the optional standard record for [mimeType]/[content] (SPEC §12): a Well-Known URI
     * record for link-shaped text, Well-Known Text for other text, otherwise a MIME record with
     * the real type and raw bytes.
     */
    fun buildStandardRecord(mimeType: String, content: ByteArray): NdefRecord {
        if (!mimeType.startsWith("text/")) return NdefRecord.createMime(mimeType, content)
        val text = content.toString(Charsets.UTF_8)
        val trimmed = text.trim()
        return if (URI_SHAPE.matches(trimmed)) NdefRecord.createUri(trimmed) else NdefRecord.createTextRecord("en", text)
    }

    private val URI_SHAPE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://\\S+$")
}
