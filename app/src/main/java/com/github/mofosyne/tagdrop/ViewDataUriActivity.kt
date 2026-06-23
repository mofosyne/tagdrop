package com.github.mofosyne.tagdrop

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.data.format.MarkdownRenderer
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.data.format.TagDropLinkResolver
import com.github.mofosyne.tagdrop.databinding.ActivityViewdatauriBinding
import com.github.mofosyne.tagdrop.util.ContentExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class ViewDataUriActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewdatauriBinding
    private lateinit var resolver: TagDropLinkResolver

    /** The cached item being viewed, if any — used by the Open/Share/Save menu actions. */
    private var exportCache: FoundCache? = null

    /** Set by [showPreviewUnavailable]; re-read by [refreshPreviewPanel] once [exportCache] resolves asynchronously. */
    private var previewMimeType: String? = null
    private var previewSizeBytes: Int = 0

    /**
     * The text/HTML/Markdown content currently on screen, tracked so the "View source" menu
     * action ([toggleSourceView]) can re-render it raw without re-fetching. Null while showing
     * non-text content (images, audio) or the "can't preview" panel -- there's no meaningful
     * raw-text view for those, so the menu action hides itself when this is null.
     */
    private var currentTextContent: TextContent? = null
    private var showingSource = false

    private data class TextContent(val mimeType: String, val bytes: ByteArray, val context: TagDropLinkResolver.PaperContext?)

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) writeToUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityViewdatauriBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = systemBars.bottom
            )
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        resolver = TagDropLinkResolver(AppDatabase.get(this))

        val dataUri = intent.getStringExtra(EXTRA_DATA_URI) ?: return

        with(binding.htmldisp.settings) {
            javaScriptEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }

        binding.htmldisp.webViewClient = object : WebViewClient() {

            /**
             * Navigation interception: tagdrop:// links and same-paper relative links
             * (resolved by the browser to https://<rootHash>.paper.tagdrop.invalid/...)
             * clicked by the user. Resolved asynchronously; loads the target as a new page.
             */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!isTagDropUrl(request.url)) return false
                handleNavigation(request.url.toString())
                return true
            }

            /**
             * Subresource interception: tagdrop:// or same-paper relative URLs in
             * <img src>, <audio src>, <video src>, <script src>, <link href>, XHR, etc.
             *
             * Called on a background thread — resolves DB synchronously via runBlocking.
             * Returns null for unknown/unresolvable resources so the browser degrades
             * gracefully (broken-image icon, silent audio failure).
             */
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!isTagDropUrl(request.url)) return null
                return resolveResource(request.url.toString())
            }
        }

        binding.previewButtonOpen.setOnClickListener { openExternally() }
        binding.previewButtonSave.setOnClickListener { saveToDevice() }

        loadInitial(dataUri)

        val cacheId = intent.getStringExtra(EXTRA_CACHE_ID)
        if (cacheId != null) {
            lifecycleScope.launch {
                exportCache = AppDatabase.get(this@ViewDataUriActivity).cacheDao().getById(cacheId)
                invalidateOptionsMenu()
                refreshPreviewPanel()
                refreshReplyBar()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_html_view, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val enabled = exportCache?.contentBytes != null
        menu.findItem(R.id.action_open_external)?.isEnabled = enabled
        menu.findItem(R.id.action_share)?.isEnabled = enabled
        menu.findItem(R.id.action_save)?.isEnabled = enabled
        menu.findItem(R.id.action_view_source)?.let {
            it.isVisible = currentTextContent != null
            it.setTitle(if (showingSource) R.string.action_view_rendered else R.string.action_view_source)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open_external -> { openExternally(); true }
        R.id.action_share -> { shareContent(); true }
        R.id.action_save -> { saveToDevice(); true }
        R.id.action_view_source -> { toggleSourceView(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Flips between the rendered view and a raw `<pre>` dump of [currentTextContent]'s original bytes. */
    private fun toggleSourceView() {
        val content = currentTextContent ?: return
        showingSource = !showingSource
        displayTextContent(content.mimeType, content.bytes, content.context)
    }

    /** Opens the cached content in another app via a chooser. */
    private fun openExternally() {
        val cache = exportCache ?: return
        val intent = ContentExporter.openIntent(this, cache) ?: return
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.action_open_external)))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.export_no_app_found))
        }
    }

    /** Shares the cached content via Android's share sheet. */
    private fun shareContent() {
        val cache = exportCache ?: return
        val intent = ContentExporter.shareIntent(this, cache) ?: return
        startActivity(Intent.createChooser(intent, getString(R.string.share_uri_title)))
    }

    /** Lets the user pick a destination and saves a copy of the cached content there. */
    private fun saveToDevice() {
        val cache = exportCache ?: return
        saveLauncher.launch(ContentExporter.suggestFilename(cache))
    }

    private fun writeToUri(uri: Uri) {
        val bytes = exportCache?.contentBytes ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.isSuccess
            }
            toast(getString(if (ok) R.string.export_saved else R.string.export_failed))
        }
    }

    /**
     * True for both navigation-link forms TagDropLinkResolver understands:
     *   tagdrop://<rootHash-hex>/<slug>
     *   https://<rootHash-hex>.paper.tagdrop.invalid/<slug>  (same-paper relative links)
     */
    private fun isTagDropUrl(uri: Uri): Boolean =
        uri.scheme == "tagdrop" || TagDropLinkResolver.isSyntheticHost(uri.host)

    /**
     * Loads the activity's initial content. If it's HTML belonging to a scanned
     * paper, load it with a same-paper synthetic base URL so relative links
     * (./about.html, ../images/logo.svg, ...) and subresources resolve to sibling
     * files in that paper. Otherwise fall back to the plain data: URI.
     */
    private fun loadInitial(dataUri: String) {
        val (mimeType, bytes) = parseDataUri(dataUri) ?: run {
            binding.htmldisp.loadUrl(dataUri)
            return
        }
        if (!isWebViewRenderable(mimeType)) {
            showPreviewUnavailable(mimeType, bytes.size)
            return
        }
        showWebView()
        if (mimeType.startsWith("text/")) {
            lifecycleScope.launch {
                val context = resolver.findPaperContext(TagDropCodec.contentId(bytes).toHex())
                displayTextContent(mimeType, bytes, context)
            }
        } else {
            currentTextContent = null
            binding.htmldisp.loadUrl(dataUri)
        }
    }

    /** Loads HTML with a synthetic same-paper base URL so its relative links resolve. */
    private fun loadHtml(html: String, rootHashHex: String, slug: String) {
        val baseUrl = TagDropLinkResolver.syntheticBaseUrl(rootHashHex, slug)
        binding.htmldisp.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    /**
     * Renders text/HTML/Markdown content -- or, while [showingSource] is toggled on via the
     * "View source" menu action, always shows the original raw bytes as preformatted text
     * instead, regardless of mimeType. This is also how vCard/Wi-Fi/calendar/... raw QR scans
     * (already plain text, no separate "rendered" form) end up visible instead of a blank page.
     * Tracked in [currentTextContent] so the toggle can re-render the same content without
     * re-fetching it.
     */
    private fun displayTextContent(mimeType: String, bytes: ByteArray, context: TagDropLinkResolver.PaperContext?) {
        currentTextContent = TextContent(mimeType, bytes, context)
        invalidateOptionsMenu()
        if (showingSource) {
            binding.htmldisp.loadDataWithBaseURL(null, plainTextHtml(String(bytes, Charsets.UTF_8)), "text/html", "UTF-8", null)
            return
        }
        when {
            mimeType.startsWith("text/html") -> {
                val html = String(bytes, Charsets.UTF_8)
                if (context != null) loadHtml(html, context.rootHashHex, context.slug)
                else binding.htmldisp.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
            mimeType.startsWith("text/markdown") -> {
                lifecycleScope.launch {
                    val css = context?.let { resolver.findStylesheet(it.rootHashHex) }
                    val html = MarkdownRenderer.toHtmlDocument(String(bytes, Charsets.UTF_8), css)
                    if (context != null) loadHtml(html, context.rootHashHex, context.slug)
                    else binding.htmldisp.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            }
            else -> binding.htmldisp.loadDataWithBaseURL(null, plainTextHtml(String(bytes, Charsets.UTF_8)), "text/html", "UTF-8", null)
        }
    }

    /**
     * Wraps raw text in a minimal HTML document so the WebView always renders it visibly,
     * matching the web reader's `<pre>`-based fallback (`tools/reader/index.html`'s
     * `showContent()`). Needed because the WebView's native `data:` URI handling only knows how
     * to display a handful of text MIME subtypes (`text/plain` works; `text/vcard` and
     * `text/calendar` -- and any other non-web text format -- silently render a blank page instead).
     */
    private fun plainTextHtml(text: String): String {
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<html><body><pre style=\"white-space:pre-wrap;word-wrap:break-word;font-family:monospace;margin:16px;\">$escaped</pre></body></html>"
    }

    /**
     * Types the WebView can meaningfully render — matches the web reader's `showContent()`
     * buckets (`tools/reader/index.html`) for cross-implementation parity. Everything else
     * (EPUB, video, archives, ...) gets the "can't preview" panel instead of a blank page.
     */
    private fun isWebViewRenderable(mimeType: String): Boolean =
        mimeType.startsWith("text/") || mimeType.startsWith("image/") || mimeType.startsWith("audio/")

    /** Hides the preview-unavailable panel and shows the WebView, e.g. when navigating to a renderable file. */
    private fun showWebView() {
        binding.previewPanel.visibility = View.GONE
        binding.htmldisp.visibility = View.VISIBLE
    }

    /** Shows the "can't preview" panel for [mimeType] content instead of a blank WebView. */
    private fun showPreviewUnavailable(mimeType: String, sizeBytes: Int) {
        currentTextContent = null
        previewMimeType = mimeType
        previewSizeBytes = sizeBytes
        binding.htmldisp.visibility = View.GONE
        binding.previewPanel.visibility = View.VISIBLE
        refreshPreviewPanel()
    }

    /** Re-renders the preview panel's text/icon/buttons from [exportCache] — called once it resolves asynchronously. */
    private fun refreshPreviewPanel() {
        val mimeType = previewMimeType ?: return
        if (binding.previewPanel.visibility != View.VISIBLE) return
        val cache = exportCache
        binding.previewIcon.text = cache?.icon ?: iconForMimeType(mimeType)
        binding.previewTitle.text = cache?.filename?.takeIf { it.isNotBlank() }
            ?: cache?.hint?.takeIf { it.isNotBlank() }
            ?: getString(R.string.preview_unavailable_title)
        binding.previewSubtitle.text = "$mimeType • ${Formatter.formatShortFileSize(this, previewSizeBytes.toLong())}"
        val canExport = cache?.contentBytes != null
        binding.previewButtonOpen.isEnabled = canExport
        binding.previewButtonSave.isEnabled = canExport
    }

    private fun iconForMimeType(mime: String): String = when {
        mime.startsWith("image/") -> "🖼"
        mime.startsWith("audio/") -> "🎵"
        mime.startsWith("video/") -> "🎬"
        mime == "application/pdf" -> "📕"
        mime == "text/calendar" -> "📅"
        mime == "text/vcard" -> "👤"
        mime.startsWith("text/") -> "📄"
        else -> "📦"
    }

    /** Re-resolves and shows/hides the reply bar (parent link + replies link) for the current [exportCache]. */
    private suspend fun refreshReplyBar() {
        val cache = exportCache
        if (cache == null) {
            binding.textReplyTo.visibility = View.GONE
            binding.textReplies.visibility = View.GONE
            return
        }
        val db = AppDatabase.get(this)
        val parent = cache.inReplyTo?.let { resolveReplyTarget(db, it) }
        val replies = db.cacheDao().getRepliesTo(cache.cacheId)
        bindReplyTo(parent)
        bindReplies(replies)
    }

    /** Resolves [idHex] (a `cache_id` or `root_hash`, SPEC §7) against both DAOs since a reply's parent can be either type. */
    private suspend fun resolveReplyTarget(db: AppDatabase, idHex: String): ReplyParent =
        db.cacheDao().getById(idHex)?.let { ReplyParent.AsCache(it) }
            ?: db.paperDao().getByRootHash(idHex)?.let { ReplyParent.AsPaper(it) }
            ?: ReplyParent.Missing(idHex)

    private fun bindReplyTo(target: ReplyParent?) {
        if (target == null) {
            binding.textReplyTo.visibility = View.GONE
            return
        }
        binding.textReplyTo.visibility = View.VISIBLE
        when (target) {
            is ReplyParent.AsCache -> {
                val label = target.cache.hint ?: target.cache.filename ?: target.cache.cacheId.take(12)
                binding.textReplyTo.text = getString(R.string.reply_to_label, label)
                binding.textReplyTo.setOnClickListener { openCollectionDetail(cacheId = target.cache.cacheId) }
            }
            is ReplyParent.AsPaper -> {
                val label = target.paper.label ?: target.paper.rootHash.take(12)
                binding.textReplyTo.text = getString(R.string.reply_to_label, label)
                binding.textReplyTo.setOnClickListener { openCollectionDetail(rootHash = target.paper.rootHash) }
            }
            is ReplyParent.Missing -> {
                binding.textReplyTo.text = getString(R.string.reply_to_not_found, target.idHex.take(12))
                binding.textReplyTo.setOnClickListener(null)
            }
        }
    }

    private fun bindReplies(replies: List<FoundCache>) {
        if (replies.isEmpty()) {
            binding.textReplies.visibility = View.GONE
            return
        }
        binding.textReplies.visibility = View.VISIBLE
        binding.textReplies.text = getString(R.string.replies_label, replies.size)
        binding.textReplies.setOnClickListener { showRepliesPicker(replies) }
    }

    /** Jumps straight to the only reply, or lets the user pick when there's more than one. */
    private fun showRepliesPicker(replies: List<FoundCache>) {
        if (replies.size == 1) {
            openCollectionDetail(cacheId = replies[0].cacheId)
            return
        }
        val labels = replies.map { it.hint ?: it.filename ?: it.cacheId.take(12) }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle(R.string.replies_dialog_title)
            .setItems(labels) { _, which -> openCollectionDetail(cacheId = replies[which].cacheId) }
            .show()
    }

    /** The resolved target of a `in_reply_to` pointer (SPEC §7) — either type of parent, or not-yet-scanned. */
    private sealed class ReplyParent {
        data class AsCache(val cache: FoundCache) : ReplyParent()
        data class AsPaper(val paper: ScannedPaper) : ReplyParent()
        data class Missing(val idHex: String) : ReplyParent()
    }

    /** Parses a "data:<mime>;base64,<payload>" URI into (mimeType, bytes), or null if not one. */
    private fun parseDataUri(dataUri: String): Pair<String, ByteArray>? {
        if (!dataUri.startsWith("data:")) return null
        val comma = dataUri.indexOf(',')
        if (comma < 0) return null
        val header = dataUri.substring(5, comma)
        if (!header.endsWith(";base64")) return null
        val bytes = runCatching {
            android.util.Base64.decode(dataUri.substring(comma + 1), android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return null
        return header.removeSuffix(";base64") to bytes
    }

    /**
     * Navigate to a new file — runs on main thread via lifecycleScope.
     * HTML loads with a synthetic same-paper base URL (so its own relative links keep
     * working); other content types replace the page with a top-level data: URI.
     */
    private fun handleNavigation(uri: String) {
        lifecycleScope.launch {
            when (val result = resolver.resolve(uri)) {
                is TagDropLinkResolver.Resolution.FileFound -> {
                    val content = result.cache.contentBytes
                        ?: run { toast(getString(R.string.content_not_stored)); return@launch }
                    exportCache = result.cache
                    showingSource = false
                    invalidateOptionsMenu()
                    refreshReplyBar()
                    if (!isWebViewRenderable(result.cache.mimeType)) {
                        showPreviewUnavailable(result.cache.mimeType, content.size)
                        return@launch
                    }
                    showWebView()
                    if (result.cache.mimeType.startsWith("text/")) {
                        val context = TagDropLinkResolver.PaperContext(result.paper.rootHash, result.slug)
                        displayTextContent(result.cache.mimeType, content, context)
                    } else {
                        currentTextContent = null
                        val dataUri = "data:${result.cache.mimeType};base64," +
                            android.util.Base64.encodeToString(content, android.util.Base64.NO_WRAP)
                        binding.htmldisp.loadUrl(dataUri)
                    }
                }
                is TagDropLinkResolver.Resolution.FileNotCached ->
                    toast(getString(R.string.file_not_scanned, result.file.slug))
                is TagDropLinkResolver.Resolution.FileNotFound ->
                    toast(getString(R.string.slug_not_found, result.slug))
                is TagDropLinkResolver.Resolution.PaperNotFound ->
                    toast(getString(R.string.paper_not_scanned))
                is TagDropLinkResolver.Resolution.Invalid ->
                    toast(getString(R.string.link_invalid))
                else -> {}
            }
        }
    }

    /**
     * Serve a tagdrop:// or same-paper resource inline — called from a background thread.
     * Used for embedded assets: images, audio, MIDI (via JS player), SVG, stylesheets, etc.
     *
     * Returns null if not found so the browser degrades gracefully rather than
     * showing an error page.
     */
    private fun resolveResource(uri: String): WebResourceResponse? {
        val result = runBlocking { resolver.resolve(uri) }
        val cache = when (result) {
            is TagDropLinkResolver.Resolution.FileFound -> result.cache
            else -> return null
        }
        val content = cache.contentBytes ?: return null
        val charset = if (cache.mimeType.startsWith("text/")) "UTF-8" else null
        return WebResourceResponse(cache.mimeType, charset, ByteArrayInputStream(content))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        const val EXTRA_DATA_URI = "extra_data_uri"
        const val EXTRA_CACHE_ID = "extra_cache_id"
    }
}
