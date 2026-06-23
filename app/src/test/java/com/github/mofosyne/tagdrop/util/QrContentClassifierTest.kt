package com.github.mofosyne.tagdrop.util

import com.google.zxing.BarcodeFormat
import org.junit.Assert.*
import org.junit.Test

/** Exercises [QrContentClassifier.classify] against representative real-world QR payloads for each recognised type. */
class QrContentClassifierTest {

    private fun classify(text: String) = QrContentClassifier.classify(text, BarcodeFormat.QR_CODE)

    @Test fun classifiesVCard() {
        val result = classify("BEGIN:VCARD\nVERSION:3.0\nN:Doe;John;;;\nFN:John Doe\nEND:VCARD")
        assertEquals("vcard", result?.tag)
        assertEquals("text/vcard", result?.mimeType)
    }

    @Test fun classifiesMeCard() {
        val result = classify("MECARD:N:Doe,John;TEL:+15551234567;;")
        assertEquals("vcard", result?.tag)
    }

    @Test fun classifiesWifiConfig() {
        val result = classify("WIFI:S:MyNetwork;T:WPA;P:secret123;;")
        assertEquals("wifi", result?.tag)
        assertNull(result?.mimeType)
    }

    @Test fun classifiesGeoUri() {
        val result = classify("geo:37.786971,-122.399677")
        assertEquals("geo", result?.tag)
    }

    @Test fun classifiesTelUri() {
        val result = classify("tel:+15551234567")
        assertEquals("tel", result?.tag)
    }

    @Test fun classifiesSmsto() {
        val result = classify("smsto:5551234567:Hello there")
        assertEquals("sms", result?.tag)
    }

    @Test fun classifiesMailto() {
        val result = classify("mailto:someone@example.com")
        assertEquals("email", result?.tag)
    }

    @Test fun classifiesPlainUrl() {
        val result = classify("https://example.com/path")
        assertEquals("url", result?.tag)
        assertNull(result?.mimeType)
    }

    @Test fun classifiesFullVCalendarEvent() {
        val result = classify(
            "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nSUMMARY:Team meeting\n" +
                "DTSTART:20260701T100000Z\nDTEND:20260701T110000Z\nEND:VEVENT\nEND:VCALENDAR"
        )
        assertEquals("calendar", result?.tag)
        assertEquals("text/calendar", result?.mimeType)
    }

    @Test fun classifiesBareVEventWithoutVCalendarWrapper() {
        val result = classify("BEGIN:VEVENT\nSUMMARY:Standalone event\nDTSTART:20260701T100000Z\nEND:VEVENT")
        assertEquals("calendar", result?.tag)
    }

    @Test fun returnsNullForUnclassifiedPlainText() {
        val result = classify("just a plain note with no special format")
        assertNull(result)
    }
}
