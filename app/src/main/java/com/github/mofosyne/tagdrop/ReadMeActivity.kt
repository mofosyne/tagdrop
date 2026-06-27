package com.github.mofosyne.tagdrop

import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.github.mofosyne.tagdrop.databinding.ActivityReadMeBinding
import java.io.ByteArrayOutputStream
import java.io.IOException

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
        
        val rawText = readRaw()
        val spannable = SpannableString(rawText)
        applyMarkdownSpans(spannable)
        binding.readmeInfo.text = spannable
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun readRaw(): String {
        return try {
            resources.openRawResource(R.raw.readme).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    // ── Markdown-style span formatter (preserved from original ReadMe.java) ──

    private fun applyMarkdownSpans(span: Spannable) {
        styleLine(span, "# ",    0xfff4585d.toInt(), 2.0f)
        styleLine(span, "\n# ",  0xFFF4A158.toInt(), 1.5f)
        styleLine(span, "\n## ", 0xFFF4A158.toInt(), 1.2f)
        styleLine(span, "\n---", 0xFFF4A158.toInt(), 1.2f)
        styleLine(span, "\n>",   0xFF89e24d.toInt(), 0.9f)
        styleLine(span, "\n - ", 0xFFA74DE3.toInt(), 1.0f)
        styleLine(span, "\n- ",  0xFFA74DE3.toInt(), 1.0f)
        styleSpan(span, " **",    "** ",    Typeface.BOLD,        "",          0xFF89e24d.toInt(), 1.0f, endAtLine = true)
        styleSpan(span, " *",     "* ",     Typeface.ITALIC,      "",          0xFF4dd8e2.toInt(), 1.0f, endAtLine = true)
        styleSpan(span, " ***",   "*** ",   Typeface.BOLD_ITALIC, "",          0xFF4de25c.toInt(), 1.0f, endAtLine = true)
        styleSpan(span, " `",     "` ",     Typeface.BOLD,        "monospace", 0xFF45c152.toInt(), 1.1f, endAtLine = true)
        styleSpan(span, "\n    ", "\n",     Typeface.BOLD,        "monospace", 0xFF45c152.toInt(), 1.1f, endAtLine = true)
        styleSpan(span, "\n```\n","\n```\n",Typeface.BOLD,        "monospace", 0xFF45c152.toInt(), 1.1f, endAtLine = false)
    }

    private fun styleLine(span: Spannable, target: String, colour: Int, size: Float) {
        val s = span.toString()
        var searchFrom = 0
        while (true) {
            val start = s.indexOf(target, searchFrom)
            if (start < 0) break
            
            var lineEnd = s.indexOf("\n", start + target.length)
            if (lineEnd < 0) lineEnd = s.length
            
            // If target starts with a newline, don't include it in the styled span
            val styleStart = if (target.startsWith("\n")) start + 1 else start
            
            if (lineEnd > styleStart) {
                span.setSpan(ForegroundColorSpan(colour), styleStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(RelativeSizeSpan(size),      styleStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(StyleSpan(Typeface.BOLD),    styleStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            
            searchFrom = lineEnd
        }
    }

    private fun styleSpan(
        span: Spannable, startTag: String, endTag: String,
        typefaceStyle: Int, fontFamily: String, colour: Int, size: Float, endAtLine: Boolean
    ) {
        val s = span.toString()
        var searchFrom = 0
        while (true) {
            val start = s.indexOf(startTag, searchFrom)
            if (start < 0) break
            
            var contentStart = start + startTag.length
            var endPos = s.indexOf(endTag, contentStart)
            
            if (endAtLine) {
                val lb = s.indexOf("\n", contentStart)
                if (lb in 0 until endPos || endPos < 0) endPos = lb
            }
            
            if (endPos < 0) {
                searchFrom = contentStart
                continue
            }
            
            val styleEnd = if (s.getOrNull(endPos) == '\n') endPos else endPos + endTag.length
            
            if (styleEnd > start) {
                span.setSpan(ForegroundColorSpan(colour), start, styleEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(RelativeSizeSpan(size),      start, styleEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(StyleSpan(typefaceStyle),    start, styleEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (fontFamily.isNotEmpty())
                    span.setSpan(TypefaceSpan(fontFamily), start, styleEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            
            searchFrom = styleEnd
        }
    }
}
