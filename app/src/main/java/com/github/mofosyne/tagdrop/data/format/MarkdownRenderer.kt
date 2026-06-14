package com.github.mofosyne.tagdrop.data.format

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Renders `text/markdown` content (SPEC §7) to a standalone HTML document, for display
 * via the same WebView path as `text/html` content (relative links, tagdrop:// resolution,
 * subresource interception all keep working).
 */
object MarkdownRenderer {
    private val parser: Parser = Parser.builder().build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().build()

    /**
     * Converts [markdown] to a full HTML document. If [css] is non-null, it's inlined as a
     * `<style>` tag in `<head>` — used for the paper-wide `style.css` sibling-file convention.
     */
    fun toHtmlDocument(markdown: String, css: String? = null): String {
        val body = renderer.render(parser.parse(markdown))
        val style = if (css != null) "<style>$css</style>" else ""
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">$style</head><body>$body</body></html>"
    }
}
