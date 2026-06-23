package com.github.mofosyne.tagdrop.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.google.zxing.client.result.AddressBookParsedResult
import com.google.zxing.client.result.CalendarParsedResult
import com.google.zxing.client.result.EmailAddressParsedResult
import com.google.zxing.client.result.ParsedResultType
import com.google.zxing.client.result.ResultParser
import com.google.zxing.client.result.SMSParsedResult
import com.google.zxing.client.result.TelParsedResult
import com.google.zxing.client.result.URIParsedResult
import com.google.zxing.client.result.WifiParsedResult

/**
 * Classifies a raw (non-TagDrop) scan's text using zxing's own `client.result` parsers --
 * the same heuristics general-purpose barcode scanner apps use to recognise vCards, Wi-Fi
 * configs, calendar events, etc. (e.g. [ResultParser.parseResult]'s [ParsedResultType.CALENDAR]
 * case matches a bare `BEGIN:VEVENT` block, not just a full `VCALENDAR` wrapper).
 *
 * Used by [com.github.mofosyne.tagdrop.ReceiveActivity.completeRawScan] to tag the cached item
 * with a `#`-hashtag-style [com.github.mofosyne.tagdrop.data.db.FoundCache.collectionTag], an
 * icon, a human-readable [Classification.title] (the contact name, SSID, event summary, ... --
 * stored as [com.github.mofosyne.tagdrop.data.db.FoundCache.hint] so it shows up everywhere a
 * cache's title is displayed, instead of falling back to "Untitled") and -- only for types that
 * correspond to a real interchange file format -- a more specific mimeType so "Open externally"
 * can hand it to a matching app (Contacts, Calendar) instead of a plain text viewer.
 *
 * Limited to the [ParsedResultType] values reachable from this app's QR_CODE/DATA_MATRIX/AZTEC-
 * only scanner config ([com.journeyapps.barcodescanner.DefaultDecoderFactory] in
 * [com.github.mofosyne.tagdrop.ReceiveActivity]) -- ISBN/PRODUCT/VIN parsers gate on 1D barcode
 * formats (EAN-13, UPC, Code 39) this app never decodes, so they can't fire here.
 */
object QrContentClassifier {

    data class Classification(val tag: String, val icon: String, val mimeType: String? = null, val title: String? = null)

    fun classify(text: String, format: BarcodeFormat): Classification? {
        val parsed = ResultParser.parseResult(Result(text, null, null, format))
        return when (parsed.type) {
            ParsedResultType.ADDRESSBOOK -> {
                val vcard = parsed as AddressBookParsedResult
                Classification("vcard", "👤", "text/vcard", vcard.names?.firstOrNull()?.takeIf { it.isNotBlank() })
            }
            ParsedResultType.CALENDAR -> {
                val event = parsed as CalendarParsedResult
                Classification("calendar", "📅", "text/calendar", event.summary?.takeIf { it.isNotBlank() })
            }
            ParsedResultType.WIFI -> {
                val wifi = parsed as WifiParsedResult
                Classification("wifi", "📶", title = wifi.ssid?.takeIf { it.isNotBlank() })
            }
            // No title: GeoParsedResult.query (when present) is the raw, unparsed "q=..." query
            // string -- not worth half-decoding for the common case (a bare "geo:lat,lon" with
            // no query at all) where it's null anyway.
            ParsedResultType.GEO -> Classification("geo", "📍")
            ParsedResultType.TEL -> {
                val tel = parsed as TelParsedResult
                Classification("tel", "📞", title = tel.number?.takeIf { it.isNotBlank() })
            }
            ParsedResultType.SMS -> {
                val sms = parsed as SMSParsedResult
                Classification("sms", "💬", title = sms.numbers?.firstOrNull()?.takeIf { it.isNotBlank() })
            }
            ParsedResultType.EMAIL_ADDRESS -> {
                val email = parsed as EmailAddressParsedResult
                Classification("email", "📧", title = email.tos?.firstOrNull()?.takeIf { it.isNotBlank() })
            }
            ParsedResultType.URI -> {
                val uri = parsed as URIParsedResult
                Classification("url", "🔗", title = uri.title?.takeIf { it.isNotBlank() } ?: uri.uri)
            }
            else -> null
        }
    }
}
