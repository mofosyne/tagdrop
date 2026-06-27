package com.github.mofosyne.tagdrop.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream

/** Shared helpers for rendering QR codes on-device with ZXing (used by Create / Create Paper). */
object QrUtils {

    /** Renders [text] as a square QR-code bitmap [sizePx] pixels wide. */
    fun encodeQr(text: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, sizePx, sizePx,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2)
        )
        val bmp = createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width)
            for (y in 0 until matrix.height)
                bmp[x, y] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        return bmp
    }

    /** Encodes [bmp] as a PNG `data:` URI, e.g. for embedding in a print/PDF WebView. */
    fun bitmapToDataUri(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return "data:image/png;base64,$b64"
    }
}
