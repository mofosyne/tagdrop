package com.github.mofosyne.tagdrop

import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.Html
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.data.format.TagDropPayload
import com.github.mofosyne.tagdrop.databinding.ActivityCreatePaperBinding
import com.github.mofosyne.tagdrop.databinding.ItemPaperFileEntryBinding
import com.github.mofosyne.tagdrop.databinding.ItemPaperQrBinding
import com.github.mofosyne.tagdrop.util.QrUtils
import com.google.zxing.WriterException

/**
 * Creates a multi-file TagDrop "paper": a manifest QR plus one QR per file, laid out
 * for printing (or "Save as PDF" via the system print dialog).
 *
 * Mirrors the Paper Layout tab of the web generator (tools/generator/index.html),
 * but runs entirely on-device using [TagDropCodec.createPaperManifest].
 */
class CreatePaperActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreatePaperBinding

    private val mimeTypes = listOf("text/plain", "text/html", "application/json", "image/svg+xml")

    private data class QrEntry(val label: String, val sub: String, val idHex: String, val uri: String)

    private var lastManifest: TagDropPayload.PaperManifest? = null
    private var lastEntries: List<QrEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreatePaperBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_create_paper)

        addFileEntry()

        binding.buttonAddFile.setOnClickListener { addFileEntry() }
        binding.buttonGeneratePaper.setOnClickListener { generatePaper() }
        binding.buttonPrintPaper.setOnClickListener { printPaper() }
    }

    private fun addFileEntry() {
        val entry = ItemPaperFileEntryBinding.inflate(layoutInflater, binding.containerFiles, false)
        entry.spinnerFileMime.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, mimeTypes
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        entry.buttonRemoveFile.setOnClickListener { binding.containerFiles.removeView(entry.root) }
        binding.containerFiles.addView(entry.root)
    }

    private fun generatePaper() {
        val label = binding.editPaperLabel.text?.toString()?.ifBlank { null }
        val set   = binding.editPaperSet.text?.toString()?.ifBlank { null }
        val slug  = binding.editPaperSlug.text?.toString()?.ifBlank { null }

        if (binding.containerFiles.childCount == 0) {
            toast(getString(R.string.paper_no_files_error)); return
        }

        val files = mutableListOf<TagDropPayload.FileEntry>()
        val fileEntries = mutableListOf<QrEntry>()

        for (i in 0 until binding.containerFiles.childCount) {
            val entry = ItemPaperFileEntryBinding.bind(binding.containerFiles.getChildAt(i))
            val fileSlug = entry.editFileSlug.text?.toString()?.trim()
            val content  = entry.editFileContent.text?.toString() ?: ""
            if (fileSlug.isNullOrBlank()) { toast(getString(R.string.paper_file_slug_error)); return }
            if (content.isBlank()) { toast(getString(R.string.paper_file_content_error, fileSlug)); return }

            val mimeType = mimeTypes[entry.spinnerFileMime.selectedItemPosition]
            val compress = entry.checkFileCompress.isChecked
            val payload = TagDropCodec.createSingle(null, fileSlug, mimeType, content.toByteArray(Charsets.UTF_8), compress)
            val uri = TagDropCodec.encode(payload)
            files.add(TagDropPayload.FileEntry(fileSlug, mimeType, payload.cacheId))
            fileEntries.add(QrEntry(fileSlug, mimeType, hex(payload.cacheId), uri))

            if (uri.length > 2000) toast(getString(R.string.qr_too_large, uri.length))
        }

        val manifest = TagDropCodec.createPaperManifest(label, set, slug, files)
        val manifestUri = TagDropCodec.encode(manifest)

        lastManifest = manifest
        lastEntries = listOf(
            QrEntry(label ?: getString(R.string.paper_manifest_label), getString(R.string.paper_manifest_sub), hex(manifest.rootHash), manifestUri)
        ) + fileEntries

        renderResults(manifest)
    }

    private fun renderResults(manifest: TagDropPayload.PaperManifest) {
        binding.containerQrGrid.removeAllViews()

        binding.textRootHash.text = getString(R.string.paper_root_hash, hex(manifest.rootHash))
        binding.textRootHash.visibility = View.VISIBLE

        for (entry in lastEntries) {
            val item = ItemPaperQrBinding.inflate(layoutInflater, binding.containerQrGrid, false)
            try {
                item.imageQr.setImageBitmap(QrUtils.encodeQr(entry.uri, 640))
            } catch (e: WriterException) {
                toast(getString(R.string.qr_encode_error))
            }
            item.textQrLabel.text = entry.label
            item.textQrSub.text = entry.sub
            item.textQrId.text = entry.idHex
            binding.containerQrGrid.addView(item.root)
        }

        binding.buttonPrintPaper.visibility = View.VISIBLE
    }

    private fun printPaper() {
        val manifest = lastManifest ?: return
        if (lastEntries.isEmpty()) return

        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        val title = manifest.label ?: getString(R.string.title_create_paper)
        val html = buildPrintHtml(manifest, lastEntries)

        val webView = WebView(this)
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val adapter = webView.createPrintDocumentAdapter(title)
                printManager.print(
                    title,
                    adapter,
                    PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .build()
                )
            }
        }
    }

    private fun buildPrintHtml(manifest: TagDropPayload.PaperManifest, entries: List<QrEntry>): String {
        val header = buildString {
            append("<h2>").append(Html.escapeHtml(manifest.label ?: getString(R.string.title_create_paper))).append("</h2>")
            val set = manifest.set
            if (set != null) {
                append("<p>Set: ").append(Html.escapeHtml(set))
                manifest.slug?.let { append(" / ").append(Html.escapeHtml(it)) }
                append("</p>")
            }
        }
        val cells = entries.joinToString("") { entry ->
            val qrDataUri = QrUtils.bitmapToDataUri(QrUtils.encodeQr(entry.uri, 512))
            """
            <div class="qr-item">
              <img src="$qrDataUri" width="180" height="180"/>
              <div class="qr-label">${Html.escapeHtml(entry.label)}</div>
              <div class="qr-sub">${Html.escapeHtml(entry.sub)}</div>
              <div class="qr-id">${entry.idHex}</div>
            </div>
            """.trimIndent()
        }
        return """
            <!DOCTYPE html><html><head><style>
            body { font-family: sans-serif; margin: 24px; }
            .qr-grid { display: flex; flex-wrap: wrap; gap: 16px; margin-top: 16px; }
            .qr-item { border: 1px solid #ccc; border-radius: 8px; padding: 12px; text-align: center;
                       width: 200px; page-break-inside: avoid; }
            .qr-item img { display: block; margin: 0 auto 8px; }
            .qr-label { font-weight: 600; font-size: 0.9rem; word-break: break-all; }
            .qr-sub   { font-size: 0.75rem; color: #888; margin-top: 2px; }
            .qr-id    { font-family: monospace; font-size: 0.7rem; color: #aaa; margin-top: 4px; }
            </style></head>
            <body>
            $header
            <div class="qr-grid">$cells</div>
            </body></html>
        """.trimIndent()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
