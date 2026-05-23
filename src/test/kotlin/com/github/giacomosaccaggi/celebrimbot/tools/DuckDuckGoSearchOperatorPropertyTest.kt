package com.github.giacomosaccaggi.celebrimbot.tools

import com.github.giacomosaccaggi.celebrimbot.io.DuckDuckGoSearchOperator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldNot
import io.kotest.matchers.string.startWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import java.lang.reflect.Method

/**
 * Property-based tests for DuckDuckGoSearchOperator.parseDuckDuckGoResponse.
 *
 * **Validates: Requirements 2.1, 2.2**
 */
class DuckDuckGoSearchOperatorPropertyTest : FunSpec({

    val operator = DuckDuckGoSearchOperator()
    val parseMethod: Method = DuckDuckGoSearchOperator::class.java
        .getDeclaredMethod("parseDuckDuckGoResponse", String::class.java)
        .also { it.isAccessible = true }

    fun parse(json: String): String = parseMethod.invoke(operator, json) as String

    // -- Generators --

    val safeText = Arb.string(1..30, Codepoint.alphanumeric())
    val safeUrl = arbitrary {
        val path = Arb.string(3..15, Codepoint.alphanumeric()).bind()
        "https://example.com/$path"
    }

    /** Generates a single RelatedTopic JSON object with Text and FirstURL string fields. */
    val topicArb = arbitrary {
        val text = safeText.bind()
        val url = safeUrl.bind()
        """{"Text": "$text", "FirstURL": "$url"}"""
    }

    /** Generates a valid DuckDuckGo API JSON response with 0..10 RelatedTopics. */
    val validResponseArb = arbitrary {
        val numTopics = Arb.int(0..10).bind()
        val topics = (1..numTopics).map { topicArb.bind() }
        val hasAbstract = Arb.boolean().bind()
        val abstractText = if (hasAbstract) safeText.bind() else ""
        val abstractUrl = if (hasAbstract) safeUrl.bind() else ""
        """
        {
          "AbstractText": "$abstractText",
          "AbstractURL": "$abstractUrl",
          "RelatedTopics": [${topics.joinToString(",")}]
        }
        """.trimIndent()
    }

    // -------------------------------------------------------------------------
    // Property 9: parseDuckDuckGoResponse handles all valid API shapes
    // **Validates: Requirements 2.1, 2.2**
    // -------------------------------------------------------------------------
    test("Property 9: parseDuckDuckGoResponse handles all valid API shapes") {
        checkAll(100, validResponseArb) { json ->
            val result = parse(json)

            // Should never return an error message
            result shouldNot startWith("Error:")

            // Count the number of topic lines (lines starting with "- ")
            val topicLines = result.lines().filter { it.startsWith("- ") }
            topicLines.size shouldBeLessThanOrEqual 5
        }
    }
})
