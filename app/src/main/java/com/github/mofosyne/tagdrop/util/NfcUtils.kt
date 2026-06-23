package com.github.mofosyne.tagdrop.util

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.github.mofosyne.tagdrop.data.format.TagDropCodec

/**
 * Builds the [NdefMessage] written to / parsed from a physical NFC tag (SPEC §12): one MIME
 * record carrying a sector's raw CBOR sequence ([TagDropCodec.sectorCbor]), optionally followed
 * by an Android Application Record so tapping the tag launches TagDrop directly even when this
 * app isn't already running.
 */
object NfcUtils {
    fun buildNdefMessage(sectorCbor: ByteArray, packageName: String, includeAppRecord: Boolean): NdefMessage {
        val records = mutableListOf(NdefRecord.createMime(TagDropCodec.NFC_MIME_TYPE, sectorCbor))
        if (includeAppRecord) records += NdefRecord.createApplicationRecord(packageName)
        return NdefMessage(records.toTypedArray())
    }
}
