package com.github.mofosyne.tagdrop.data.format

import org.junit.Assert.*
import org.junit.Test

class MarkdownRendererTest {

    @Test fun rendersBasicMarkdownToHtml() {
        val html = MarkdownRenderer.toHtmlDocument("# Hello\n\nThis is **bold** text.")
        assertTrue(html.contains("<h1>Hello</h1>"))
        assertTrue(html.contains("<strong>bold</strong>"))
    }

    @Test fun wrapsInFullHtmlDocumentWithoutCss() {
        val html = MarkdownRenderer.toHtmlDocument("hi")
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<head><meta charset=\"utf-8\"></head>"))
        assertFalse(html.contains("<style>"))
    }

    @Test fun inlinesCssIntoHead() {
        val html = MarkdownRenderer.toHtmlDocument("hi", css = "body { color: red; }")
        assertTrue(html.contains("<style>body { color: red; }</style>"))
        // <style> must come before <body> so it applies to the rendered content
        assertTrue(html.indexOf("<style>") < html.indexOf("<body>"))
    }

    @Test fun preservesTagDropLinksForInterception() {
        val html = MarkdownRenderer.toHtmlDocument("[map](tagdrop://abc123/map)")
        assertTrue(html.contains("href=\"tagdrop://abc123/map\""))
    }

    @Test fun rendersGfmPipeTables() {
        val html = MarkdownRenderer.toHtmlDocument(
            "| Key | Type |\n|---|---|\n| 1 | uint |\n"
        )
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<th>Key</th>"))
        assertTrue(html.contains("<td>uint</td>"))
    }
}
