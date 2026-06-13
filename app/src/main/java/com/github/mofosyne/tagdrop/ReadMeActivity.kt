package com.github.mofosyne.tagdrop

import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.appcompat.app.AppCompatActivity
import com.github.mofosyne.tagdrop.databinding.ActivityReadMeBinding
import java.io.ByteArrayOutputStream
import java.io.IOException

class ReadMeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReadMeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReadMeBinding.inflate(layoutInflater)
        setContentView(binding.root)
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
        val out = ByteArrayOutputStream()
        try {
            resources.openRawResource(R.raw.readme).use { stream ->
                var b = stream.read()
                while (b != -1) { out.write(b); b = stream.read() }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return out.toString()
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
            
            span.setSpan(ForegroundColorSpan(colour), start, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(RelativeSizeSpan(size),      start, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(StyleSpan(Typeface.BOLD),    start, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            
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
            
            var endPos = s.indexOf(endTag, start + startTag.length)
            if (endAtLine) {
                val lb = s.indexOf("\n", start + startTag.length)
                if (lb in 0 until endPos || endPos < 0) endPos = lb
            }
            if (endPos < 0) break
            
            val finalEnd = endPos + endTag.length
            span.setSpan(ForegroundColorSpan(colour), start, finalEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(RelativeSizeSpan(size),      start, finalEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(StyleSpan(typefaceStyle),    start, finalEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (fontFamily.isNotEmpty())
                span.setSpan(TypefaceSpan(fontFamily), start, finalEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            searchFrom = finalEnd
        }
    }
}
