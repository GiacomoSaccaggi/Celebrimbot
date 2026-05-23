package com.github.giacomosaccaggi.celebrimbot.parser

import org.junit.Assert.*
import org.junit.Test

class PlanParserTest {

    // --- sanitizeJson ---

    @Test
    fun `sanitizeJson strips markdown json fences`() {
        val input = "```json\n{\"key\": \"value\"}\n```"
        val result = PlanParser.sanitizeJson(input)
        assertEquals("{\"key\": \"value\"}", result)
    }

    @Test
    fun `sanitizeJson strips plain markdown fences`() {
        val input = "```\n{\"key\": \"value\"}\n```"
        val result = PlanParser.sanitizeJson(input)
        assertEquals("{\"key\": \"value\"}", result)
    }

    @Test
    fun `sanitizeJson replaces literal newlines inside strings with spaces`() {
        val input = "{\"key\": \"line1\nline2\"}"
        val result = PlanParser.sanitizeJson(input)
        assertEquals("{\"key\": \"line1 line2\"}", result)
    }

    @Test
    fun `sanitizeJson preserves escaped sequences`() {
        val input = "{\"key\": \"has\\nnewline and \\\"quotes\\\"\"}"
        val result = PlanParser.sanitizeJson(input)
        assertEquals("{\"key\": \"has\\nnewline and \\\"quotes\\\"\"}", result)
    }

    @Test
    fun `sanitizeJson passes through valid json without fences`() {
        val input = "{\"strategy\": \"direct\", \"response\": \"hello\"}"
        val result = PlanParser.sanitizeJson(input)
        assertEquals(input, result)
    }

    // --- extractCode ---

    @Test
    fun `extractCode extracts content from fenced code block`() {
        val input = "```\nprint('hello')\n```"
        val result = PlanParser.extractCode(input)
        assertEquals("print('hello')\n", result)
    }

    @Test
    fun `extractCode returns null when no fence present`() {
        val input = "just plain text without code fences"
        val result = PlanParser.extractCode(input)
        assertNull(result)
    }

    @Test
    fun `extractCode ignores language specifier after backticks`() {
        val input = "```python\nprint('hello')\n```"
        val result = PlanParser.extractCode(input)
        assertEquals("print('hello')\n", result)
    }

    // --- markdownToHtml ---

    @Test
    fun `markdownToHtml converts bold`() {
        val result = PlanParser.markdownToHtml("**bold text**")
        assertTrue(result.contains("<b>bold text</b>"))
    }

    @Test
    fun `markdownToHtml converts italic`() {
        val result = PlanParser.markdownToHtml("*italic text*")
        assertTrue(result.contains("<i>italic text</i>"))
    }

    @Test
    fun `markdownToHtml converts inline code`() {
        val result = PlanParser.markdownToHtml("`some code`")
        assertTrue(result.contains("<code>some code</code>"))
    }

    @Test
    fun `markdownToHtml escapes HTML entities`() {
        val result = PlanParser.markdownToHtml("<script>alert('x')</script>")
        assertTrue(result.contains("&lt;"))
        assertTrue(result.contains("&gt;"))
        assertFalse(result.contains("<script>"))
    }

    @Test
    fun `markdownToHtml converts links`() {
        val result = PlanParser.markdownToHtml("[click here](https://example.com)")
        assertTrue(result.contains("<a href='https://example.com'>click here</a>"))
    }

    // --- inferPlanFromPrompt ---

    @Test
    fun `inferPlanFromPrompt detects delete patterns`() {
        val result = PlanParser.inferPlanFromPrompt("elimina src/foo.py")
        assertEquals("plan", result.strategy)
        assertEquals("delete_file", result.tasks.first().action)
        assertEquals("src/foo.py", result.tasks.first().target)
    }

    @Test
    fun `inferPlanFromPrompt detects search patterns`() {
        val result = PlanParser.inferPlanFromPrompt("cerca kotlin coroutines")
        assertEquals("plan", result.strategy)
        assertEquals("web_search", result.tasks.first().action)
    }

    @Test
    fun `inferPlanFromPrompt detects list patterns`() {
        val result = PlanParser.inferPlanFromPrompt("lista file")
        assertEquals("plan", result.strategy)
        assertEquals("list_files", result.tasks.first().action)
    }

    @Test
    fun `inferPlanFromPrompt detects git patterns`() {
        val result = PlanParser.inferPlanFromPrompt("git status")
        assertEquals("plan", result.strategy)
        assertEquals("git_status", result.tasks.first().action)
    }

    @Test
    fun `inferPlanFromPrompt detects write patterns`() {
        val result = PlanParser.inferPlanFromPrompt("crea un file hello.py")
        assertEquals("plan", result.strategy)
        assertEquals("write_code", result.tasks.first().action)
        assertEquals("hello.py", result.tasks.first().target)
    }

    @Test
    fun `inferPlanFromPrompt returns unknown for unrecognized input`() {
        val result = PlanParser.inferPlanFromPrompt("what is the meaning of life")
        assertEquals("unknown", result.strategy)
    }

    // --- escapeHtml ---

    @Test
    fun `escapeHtml escapes ampersand`() {
        assertEquals("a &amp; b", PlanParser.escapeHtml("a & b"))
    }

    @Test
    fun `escapeHtml escapes less than and greater than`() {
        assertEquals("&lt;div&gt;", PlanParser.escapeHtml("<div>"))
    }

    // --- cleanForHistory ---

    @Test
    fun `cleanForHistory strips HTML tags`() {
        val result = PlanParser.cleanForHistory("<b>hello</b> <i>world</i>")
        assertEquals("hello world", result)
    }

    @Test
    fun `cleanForHistory truncates to 300 chars`() {
        val longText = "a".repeat(500)
        val result = PlanParser.cleanForHistory(longText)
        assertEquals(300, result.length)
    }
}
