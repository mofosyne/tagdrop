package com.github.mofosyne.tagdrop

import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.TextView
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
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_activity_read_me)
        applyMarkdownSpans(binding.readmeInfo, readRaw())
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

    private fun applyMarkdownSpans(tv: TextView, text: String) {
        tv.setText(text, TextView.BufferType.SPANNABLE)
        styleLine(tv, "# ",    0xfff4585d.toInt(), 2.0f)
        styleLine(tv, "\n# ",  0xFFF4A158.toInt(), 1.5f)
        styleLine(tv, "\n## ", 0xFFF4A158.toInt(), 1.2f)
        styleLine(tv, "\n---", 0xFFF4A158.toInt(), 1.2f)
        styleLine(tv, "\n>",   0xFF89e24d.toInt(), 0.9f)
        styleLine(tv, "\n - ", 0xFFA74DE3.toInt(), 1.0f)
        styleLine(tv, "\n- ",  0xFFA74DE3.toInt(), 1.0f)
        styleSpan(tv, " **",    "** ",    Typeface.BOLD,        "",          0xFF89e24d.toInt(), 1.0f, endAtLine = true)
        styleSpan(tv, " *",     "* ",     Typeface.ITALIC,      "",          0xFF4dd8e2.toInt(), 1.0f, endAtLine = true)
        styleSpan(tv, " ***",   "*** ",   Typeface.BOLD_ITALIC, "",          0xFF4de25c.toInt(), 1.0f, endAtLine = true)
        styleSpan(tv, " `",     "` ",     Typeface.BOLD,        "monospace", 0xFF45c152.toInt(), 1.1f, endAtLine = true)
        styleSpan(tv, "\n    ", "\n",     Typeface.BOLD,        "monospace", 0xFF45c152.toInt(), 1.1f, endAtLine = true)
        styleSpan(tv, "\n```\n","\n```\n",Typeface.BOLD,        "monospace", 0xFF45c152.toInt(), 1.1f, endAtLine = false)
    }

    private fun styleLine(tv: TextView, target: String, colour: Int, size: Float) {
        val s = tv.text.toString()
        val span = tv.text as Spannable
        var end = 0
        while (true) {
            val start = s.indexOf(target, end - 1)
            end = s.indexOf("\n", start + 1)
            if (start < 0 || end < 0) break
            if (end > start) {
                span.setSpan(ForegroundColorSpan(colour), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(RelativeSizeSpan(size),      start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(StyleSpan(Typeface.BOLD),    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        tv.text = span
    }

    private fun styleSpan(
        tv: TextView, startTag: String, endTag: String,
        typefaceStyle: Int, fontFamily: String, colour: Int, size: Float, endAtLine: Boolean
    ) {
        val s = tv.text.toString()
        val span = tv.text as Spannable
        var end = 0
        while (true) {
            val start = s.indexOf(startTag, end - 1)
            var endPos = s.indexOf(endTag, start + 1 + startTag.length)
            if (endAtLine) {
                val lb = s.indexOf("\n", start + 1 + startTag.length)
                if (lb in 0 until endPos || endPos < 0) endPos = lb
            }
            if (start < 0 || endPos < 0) break
            endPos += endTag.length
            if (endPos > start) {
                span.setSpan(ForegroundColorSpan(colour), start, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(RelativeSizeSpan(size),      start, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(StyleSpan(typefaceStyle),    start, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (fontFamily.isNotEmpty())
                    span.setSpan(TypefaceSpan(fontFamily), start, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        tv.text = span
    }
}
