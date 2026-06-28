package com.github.mofosyne.tagdrop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.github.mofosyne.tagdrop.data.format.MarkdownRenderer
import com.github.mofosyne.tagdrop.databinding.ActivityReadMeBinding

class ReadMeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReadMeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityReadMeBinding.inflate(layoutInflater)
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
        title = getString(R.string.title_activity_read_me)

        // Matches the system "Font size" accessibility setting, since a WebView's text
        // otherwise ignores it (unlike a TextView, which used to scale automatically).
        binding.readmeWebView.settings.textZoom = (resources.configuration.fontScale * 100).toInt()
        binding.readmeWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme != "http" && request.url.scheme != "https") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
        }

        val html = MarkdownRenderer.toHtmlDocument(readRaw(), README_CSS)
        binding.readmeWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun readRaw(): String {
        return try {
            resources.openRawResource(R.raw.readme).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        /** Brand palette from ARTASSET/icon/HISTORY.md, with a `prefers-color-scheme: dark` variant. */
        private const val README_CSS = """
            body { margin: 16px; font-family: sans-serif; font-size: 15px; line-height: 1.5;
                   color: #1a1a1a; background: #ffffff; }
            h1, h2 { color: #D9652B; }
            h1 { font-size: 1.6em; margin-top: 0; }
            h2 { font-size: 1.25em; margin-top: 1.4em; border-bottom: 1px solid #e0d7c8; padding-bottom: 4px; }
            a { color: #2A3941; }
            blockquote { margin: 0.8em 0; padding: 0.4em 0.8em; border-left: 4px solid #D9652B;
                         background: #F3EDE1; color: #333333; }
            hr { border: none; border-top: 1px solid #dddddd; margin: 1.5em 0; }
            strong { color: #2A3941; }
            code { font-family: monospace; }
            li { margin-bottom: 0.4em; }
            @media (prefers-color-scheme: dark) {
                body { color: #e8e8e8; background: #121212; }
                h1, h2 { color: #f08a4b; }
                h2 { border-bottom-color: #333333; }
                a { color: #8fb8c9; }
                blockquote { background: #1e1e1e; color: #cccccc; border-left-color: #f08a4b; }
                hr { border-top-color: #333333; }
                strong { color: #cfe3ea; }
            }
        """
    }
}
