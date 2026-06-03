package com.github.mofosyne.tagdrop

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.format.TagDropLinkResolver
import com.github.mofosyne.tagdrop.databinding.ActivityViewdatauriBinding
import kotlinx.coroutines.launch

class ViewDataUriActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewdatauriBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewdatauriBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val dataUri = intent.getStringExtra(EXTRA_DATA_URI) ?: return

        with(binding.htmldisp.settings) {
            javaScriptEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }

        binding.htmldisp.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("tagdrop://")) {
                    handleTagDropLink(url)
                    return true
                }
                return false
            }
        }

        binding.htmldisp.loadUrl(dataUri)
    }

    private fun handleTagDropLink(uri: String) {
        lifecycleScope.launch {
            val resolver = TagDropLinkResolver(AppDatabase.get(this@ViewDataUriActivity))
            when (val result = resolver.resolve(uri)) {
                is TagDropLinkResolver.Resolution.FileFound -> {
                    val content = result.cache.contentBytes ?: run {
                        toast(getString(R.string.content_not_stored)); return@launch
                    }
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
                else -> {}
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        const val EXTRA_DATA_URI = "extra_data_uri"
    }
}
