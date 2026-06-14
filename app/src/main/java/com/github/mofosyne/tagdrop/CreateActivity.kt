package com.github.mofosyne.tagdrop

import android.content.Intent
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.databinding.ActivityCreateBinding
import com.github.mofosyne.tagdrop.util.QrUtils
import com.google.zxing.WriterException
import kotlinx.coroutines.launch

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

    private val mimeTypes = listOf("text/plain", "text/html", "text/markdown", "application/json", "image/svg+xml")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
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
        val icon     = binding.editIcon.text?.toString()?.ifBlank { null }
        val mimeType = mimeTypes[binding.spinnerMime.selectedItemPosition]
        val compress = binding.checkCompress.isChecked

        val rawContent = content.toByteArray(Charsets.UTF_8)
        val payload = TagDropCodec.createSingle(hint, filename, mimeType,
                          rawContent, compress, icon = icon)
        val uri = TagDropCodec.encode(payload)
        lastUri = uri
        lastPayloadHint = hint ?: filename

        if (uri.length > TagDropCodec.MAX_URI_LENGTH) toast(getString(R.string.qr_too_large, uri.length))

        try {
            binding.imageQr.setImageBitmap(QrUtils.encodeQr(uri, 640))
            val idHex = payload.cacheId.joinToString("") { "%02x".format(it) }
            binding.textCacheId.text = getString(R.string.qr_cache_id, idHex)
            binding.textUri.text = uri
            setResultsVisible(true)
            saveToMyDrops(idHex, hint, filename, mimeType, rawContent, icon)
        } catch (e: WriterException) {
            toast(getString(R.string.qr_encode_error))
        }
    }

    /** Persists the generated cache to the local DB (My Drops) so it can be revisited, re-shared, or re-printed later. */
    private fun saveToMyDrops(
        cacheId: String, hint: String?, filename: String?, mimeType: String, content: ByteArray, icon: String?
    ) {
        lifecycleScope.launch {
            AppDatabase.get(this@CreateActivity).cacheDao().insert(
                FoundCache(
                    cacheId      = cacheId,
                    discoveredAt = System.currentTimeMillis(),
                    hint         = hint,
                    filename     = filename,
                    mimeType     = mimeType,
                    contentBytes = content,
                    icon         = icon,
                    createdByMe  = true
                )
            )
            toast(getString(R.string.cache_saved))
        }
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
        val qrDataUri = QrUtils.bitmapToDataUri(QrUtils.encodeQr(lastUri, 512))
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
