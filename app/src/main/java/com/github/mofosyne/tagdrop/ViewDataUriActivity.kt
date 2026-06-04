package com.github.mofosyne.tagdrop

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.format.TagDropLinkResolver
import com.github.mofosyne.tagdrop.databinding.ActivityViewdatauriBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

class ViewDataUriActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewdatauriBinding
    private lateinit var resolver: TagDropLinkResolver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewdatauriBinding.inflate(layoutInflater)
        setContentView(binding.root)
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
             * Navigation interception: tagdrop:// links clicked by the user.
             * Resolved asynchronously; loads the target as a new data: URI page.
             */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("tagdrop://")) {
                    handleNavigation(url)
                    return true
                }
                return false
            }

            /**
             * Subresource interception: tagdrop:// URLs in <img src>, <audio src>,
             * <video src>, <script src>, <link href>, XHR, etc.
             *
             * Called on a background thread — resolves DB synchronously via runBlocking.
             * Returns null for unknown/unresolvable resources so the browser degrades
             * gracefully (broken-image icon, silent audio failure).
             */
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (!url.startsWith("tagdrop://")) return null
                return resolveResource(url)
            }
        }

        binding.htmldisp.loadUrl(dataUri)
    }

    /**
     * Navigate to a new file — runs on main thread via lifecycleScope.
     * Loads the resolved content as a top-level data: URI (replaces current page).
     */
    private fun handleNavigation(uri: String) {
        lifecycleScope.launch {
            when (val result = resolver.resolve(uri)) {
                is TagDropLinkResolver.Resolution.FileFound -> {
                    val content = result.cache.contentBytes
                        ?: run { toast(getString(R.string.content_not_stored)); return@launch }
                    val dataUri = "data:${result.cache.mimeType};base64," +
                        android.util.Base64.encodeToString(content, android.util.Base64.NO_WRAP)
                    binding.htmldisp.loadUrl(dataUri)
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
     * Serve a tagdrop:// resource inline — called from a background thread.
     * Used for embedded assets: images, audio, MIDI (via JS player), SVG, etc.
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

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        const val EXTRA_DATA_URI = "extra_data_uri"
    }
}
