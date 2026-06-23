package com.github.mofosyne.tagdrop.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.google.zxing.client.result.ParsedResultType
import com.google.zxing.client.result.ResultParser

/**
 * Classifies a raw (non-TagDrop) scan's text using zxing's own `client.result` parsers --
 * the same heuristics general-purpose barcode scanner apps use to recognise vCards, Wi-Fi
 * configs, calendar events, etc. (e.g. [ResultParser.parseResult]'s [ParsedResultType.CALENDAR]
 * case matches a bare `BEGIN:VEVENT` block, not just a full `VCALENDAR` wrapper).
 *
 * Used by [com.github.mofosyne.tagdrop.ReceiveActivity.completeRawScan] to tag the cached item
 * with a `#`-hashtag-style [com.github.mofosyne.tagdrop.data.db.FoundCache.collectionTag] and
 * icon, and -- only for types that correspond to a real interchange file format -- a more
 * specific mimeType so "Open externally" can hand it to a matching app (Contacts, Calendar)
 * instead of a plain text viewer.
 *
 * Limited to the [ParsedResultType] values reachable from this app's QR_CODE/DATA_MATRIX/AZTEC-
 * only scanner config ([com.journeyapps.barcodescanner.DefaultDecoderFactory] in
 * [com.github.mofosyne.tagdrop.ReceiveActivity]) -- ISBN/PRODUCT/VIN parsers gate on 1D barcode
 * formats (EAN-13, UPC, Code 39) this app never decodes, so they can't fire here.
 */
object QrContentClassifier {

    data class Classification(val tag: String, val icon: String, val mimeType: String? = null)

    fun classify(text: String, format: BarcodeFormat): Classification? {
        val parsed = ResultParser.parseResult(Result(text, null, null, format))
        return when (parsed.type) {
            ParsedResultType.ADDRESSBOOK -> Classification("vcard", "👤", "text/vcard")
            ParsedResultType.CALENDAR -> Classification("calendar", "📅", "text/calendar")
            ParsedResultType.WIFI -> Classification("wifi", "📶")
            ParsedResultType.GEO -> Classification("geo", "📍")
            ParsedResultType.TEL -> Classification("tel", "📞")
            ParsedResultType.SMS -> Classification("sms", "💬")
            ParsedResultType.EMAIL_ADDRESS -> Classification("email", "📧")
            ParsedResultType.URI -> Classification("url", "🔗")
            else -> null
        }
    }
}
