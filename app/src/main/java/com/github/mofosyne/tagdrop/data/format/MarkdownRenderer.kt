package com.github.mofosyne.tagdrop.data.format

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Renders `text/markdown` content (SPEC §7) to a standalone HTML document, for display
 * via the same WebView path as `text/html` content (relative links, tagdrop:// resolution,
 * subresource interception all keep working).
 *
 * Includes the GFM tables extension — needed for SPEC.md's pipe tables (rendered in-app by
 * SpecActivity), not just author-supplied `text/markdown` content.
 */
object MarkdownRenderer {
    private val extensions = listOf(TablesExtension.create())
    private val parser: Parser = Parser.builder().extensions(extensions).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().extensions(extensions).build()

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
