package com.github.mofosyne.tagdrop

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.databinding.ActivityCreateBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Creates a TagDrop Single-code cache from typed or pasted content.
 *
 * Generates a content-addressed QR code on-device using ZXing — no server needed.
 * Print support uses a WebView to render a clean print page (label + QR + URI).
 * Content too large for one QR (>~2 KB after encoding) gets a warning; for larger
 * payloads or paper layouts use the static HTML generator in tools/generator/.
 */
class CreateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateBinding
    private var lastUri: String = ""
    private var lastPayloadHint: String? = null

    private val mimeTypes = listOf("text/plain", "text/html", "application/json", "image/svg+xml")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_create)

        binding.spinnerMime.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, mimeTypes
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.buttonGenerate.setOnClickListener { generate() }
        binding.buttonShare.setOnClickListener    { shareUri() }
        binding.buttonPrint.setOnClickListener    { printQr() }
    }

    private fun generate() {
        val content = binding.editContent.text?.toString() ?: ""
        if (content.isBlank()) { toast(getString(R.string.content_empty_error)); return }

        val hint     = binding.editHint.text?.toString()?.ifBlank { null }
        val filename = binding.editFilename.text?.toString()?.ifBlank { null }
        val mimeType = mimeTypes[binding.spinnerMime.selectedItemPosition]
        val compress = binding.checkCompress.isChecked

        val payload = TagDropCodec.createSingle(hint, filename, mimeType,
                          content.toByteArray(Charsets.UTF_8), compress)
        val uri = TagDropCodec.encode(payload)
        lastUri = uri
        lastPayloadHint = hint ?: filename

        if (uri.length > 2000) toast(getString(R.string.qr_too_large, uri.length))

        try {
            binding.imageQr.setImageBitmap(encodeQr(uri, 640))
            val idHex = payload.cacheId.joinToString("") { "%02x".format(it) }
            binding.textCacheId.text = getString(R.string.qr_cache_id, idHex)
            binding.textUri.text = uri
            setResultsVisible(true)
        } catch (e: WriterException) {
            toast(getString(R.string.qr_encode_error))
        }
    }

    private fun encodeQr(text: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, sizePx, sizePx,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2)
        )
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width)
            for (y in 0 until matrix.height)
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }

    private fun shareUri() {
        if (lastUri.isBlank()) return
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, lastUri)
            },
            getString(R.string.share_uri_title)
        ))
    }

    private fun printQr() {
        if (lastUri.isBlank()) return
        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        val label = lastPayloadHint ?: getString(R.string.app_name)

        // Render a clean print page: label + QR as a data URI image + raw URI text
        val qrDataUri = bitmapToDataUri(encodeQr(lastUri, 512))
        val html = buildPrintHtml(label, qrDataUri, lastUri)

        val webView = WebView(this)
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val adapter = webView.createPrintDocumentAdapter(label)
                printManager.print(
                    label,
                    adapter,
                    PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .build()
                )
            }
        }
    }

    private fun buildPrintHtml(label: String, qrDataUri: String, uri: String): String = """
        <!DOCTYPE html><html><body style="font-family:sans-serif;text-align:center;margin:40px">
        <h2>${android.text.Html.escapeHtml(label)}</h2>
        <img src="$qrDataUri" width="300" height="300" style="display:block;margin:24px auto"/>
        <p style="font-family:monospace;font-size:8px;word-break:break-all;color:#555">
            ${android.text.Html.escapeHtml(uri)}
        </p>
        </body></html>
    """.trimIndent()

    private fun bitmapToDataUri(bmp: Bitmap): String {
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        return "data:image/png;base64,$b64"
    }

    private fun setResultsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        binding.imageQr.visibility     = v
        binding.textCacheId.visibility  = v
        binding.buttonShare.visibility  = v
        binding.buttonPrint.visibility  = v
        binding.textUri.visibility     = v
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
