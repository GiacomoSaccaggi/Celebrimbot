package com.github.giacomosaccaggi.celebrimbot.io

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

class DuckDuckGoSearchOperatorTest {

    private lateinit var operator: DuckDuckGoSearchOperator
    private lateinit var parseMethod: Method

    @Before
    fun setUp() {
        operator = DuckDuckGoSearchOperator()
        parseMethod = DuckDuckGoSearchOperator::class.java
            .getDeclaredMethod("parseDuckDuckGoResponse", String::class.java)
            .also { it.isAccessible = true }
    }

    private fun parse(json: String): String = parseMethod.invoke(operator, json) as String

    @Test
    fun `valid response with AbstractText and RelatedTopics`() {
        val json = """
        {
          "AbstractText": "Kotlin is a programming language",
          "AbstractURL": "https://kotlinlang.org",
          "RelatedTopics": [
            {"Text": "Kotlin programming", "FirstURL": "https://example.com/1"},
            {"Text": "Kotlin coroutines", "FirstURL": "https://example.com/2"}
          ]
        }
        """.trimIndent()

        val result = parse(json)
        assertTrue(result.contains("Summary: Kotlin is a programming language"))
        assertTrue(result.contains("Source: https://kotlinlang.org"))
        assertTrue(result.contains("- Kotlin programming (https://example.com/1)"))
        assertTrue(result.contains("- Kotlin coroutines (https://example.com/2)"))
    }

    @Test
    fun `response with FirstURL as string extracts URLs correctly`() {
        val json = """
        {
          "AbstractText": "",
          "AbstractURL": "",
          "RelatedTopics": [
            {"Text": "Result one", "FirstURL": "https://example.com/result1"},
            {"Text": "Result two", "FirstURL": "https://example.com/result2"}
          ]
        }
        """.trimIndent()

        val result = parse(json)
        assertTrue(result.contains("- Result one (https://example.com/result1)"))
        assertTrue(result.contains("- Result two (https://example.com/result2)"))
    }

    @Test
    fun `response with more than 5 topics truncates to 5`() {
        val topics = (1..8).joinToString(",") {
            """{"Text": "Topic $it", "FirstURL": "https://example.com/$it"}"""
        }
        val json = """
        {
          "AbstractText": "",
          "AbstractURL": "",
          "RelatedTopics": [$topics]
        }
        """.trimIndent()

        val result = parse(json)
        assertTrue(result.contains("Topic 5"))
        assertFalse(result.contains("Topic 6"))
    }

    @Test
    fun `empty response returns no results message`() {
        val json = """
        {
          "AbstractText": "",
          "AbstractURL": "",
          "RelatedTopics": []
        }
        """.trimIndent()

        val result = parse(json)
        assertEquals("No results found for the query.", result)
    }

    @Test
    fun `malformed JSON returns error message without exception`() {
        val result = parse("this is not json at all {{{")
        assertTrue(result.startsWith("Error: could not parse search response"))
    }

    @Test
    fun `response with AbstractURL includes source in output`() {
        val json = """
        {
          "AbstractText": "Some summary",
          "AbstractURL": "https://en.wikipedia.org/wiki/Something",
          "RelatedTopics": []
        }
        """.trimIndent()

        val result = parse(json)
        assertTrue(result.contains("Source: https://en.wikipedia.org/wiki/Something"))
    }
}
