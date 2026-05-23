package com.github.giacomosaccaggi.celebrimbot.parser

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import io.kotest.matchers.shouldBe

/**
 * Property-based tests for PlanParser.
 *
 * **Validates: Requirements 1.2, 1.3, 1.4, 1.6, 1.9, 8.8**
 */
class PlanParserPropertyTest : FunSpec({

    // -- Generators --

    val alphaNum = Arb.string(1..8, Codepoint.alphanumeric())
    val safeChars = Arb.string(1..20, Codepoint.alphanumeric())

    /** Generates simple valid JSON objects with string values. */
    val validJsonArb: Arb<String> = arbitrary {
        val numPairs = Arb.int(1..4).bind()
        val pairs = (1..numPairs).map {
            val k = alphaNum.bind()
            val v = safeChars.bind()
            "\"$k\": \"$v\""
        }
        "{ ${pairs.joinToString(", ")} }"
    }

    /** Wraps JSON in optional markdown fences. */
    val fencedJsonArb: Arb<String> = arbitrary {
        val json = validJsonArb.bind()
        val fenceType = Arb.int(0..2).bind()
        when (fenceType) {
            0 -> json
            1 -> "```\n$json\n```"
            else -> "```json\n$json\n```"
        }
    }

    // -------------------------------------------------------------------------
    // Property 1: sanitizeJson preserves valid JSON structure
    // **Validates: Requirements 1.2, 8.8**
    // -------------------------------------------------------------------------
    test("Property 1: sanitizeJson preserves valid JSON structure") {
        checkAll(100, fencedJsonArb) { input ->
            val result = PlanParser.sanitizeJson(input)
            val stripped = input.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val expectedElement = JsonParser.parseString(stripped)
            val actualElement = JsonParser.parseString(result)
            actualElement shouldBe expectedElement
        }
    }

    // -------------------------------------------------------------------------
    // Property 2: sanitizeJson removes literal newlines from strings
    // **Validates: Requirement 1.3**
    // -------------------------------------------------------------------------
    test("Property 2: sanitizeJson removes literal newlines from strings") {
        val jsonWithNewlinesArb: Arb<String> = arbitrary {
            val key = alphaNum.bind()
            val part1 = safeChars.bind()
            val part2 = safeChars.bind()
            "{\"$key\": \"$part1\n$part2\"}"
        }

        checkAll(100, jsonWithNewlinesArb) { input ->
            val result = PlanParser.sanitizeJson(input)
            // Walk through result: inside string values, there should be no literal newlines
            var inString = false
            var i = 0
            while (i < result.length) {
                val c = result[i]
                when {
                    c == '\\' && inString -> i++ // skip escaped char
                    c == '"' -> inString = !inString
                    (c == '\n' || c == '\r') && inString ->
                        throw AssertionError("Found literal newline inside string at position $i in: $result")
                }
                i++
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 3: extractCode round-trip
    // **Validates: Requirement 1.4**
    // -------------------------------------------------------------------------
    test("Property 3: extractCode round-trip") {
        val codeArb: Arb<String> = Arb.string(1..50, Codepoint.alphanumeric())

        checkAll(100, codeArb) { code ->
            val wrapped = "```\n$code\n```"
            val extracted = PlanParser.extractCode(wrapped)
            extracted shouldBe "$code\n"
        }
    }

    // -------------------------------------------------------------------------
    // Property 4: markdownToHtml escapes HTML entities
    // **Validates: Requirement 1.6**
    // -------------------------------------------------------------------------
    test("Property 4: markdownToHtml escapes HTML entities") {
        // Generate strings with potential HTML characters
        val inputArb: Arb<String> = arbitrary {
            val base = Arb.string(1..40, Codepoint.alphanumeric()).bind()
            val angleCount = Arb.int(0..3).bind()
            val parts = mutableListOf(base)
            repeat(angleCount) {
                val extra = Arb.element(listOf("<", ">", "&", "plain")).bind()
                parts.add(extra)
                parts.add(Arb.string(1..5, Codepoint.alphanumeric()).bind())
            }
            parts.joinToString("")
        }

        // Known HTML tags that markdownToHtml can produce
        val allowedTagPattern = Regex("</?(?:b|i|code|pre|br|a)(?: [^>]*)?>")

        checkAll(100, inputArb) { input ->
            val result = PlanParser.markdownToHtml(input)
            // Remove all known allowed HTML tags
            val withoutKnownTags = allowedTagPattern.replace(result, "")
            // After removal, there should be no raw < or > left
            withoutKnownTags.contains('<') shouldBe false
            withoutKnownTags.contains('>') shouldBe false
        }
    }

    // -------------------------------------------------------------------------
    // Property 5: inferPlanFromPrompt determinism
    // **Validates: Requirement 1.9**
    // -------------------------------------------------------------------------
    test("Property 5: inferPlanFromPrompt determinism") {
        val promptArb: Arb<String> = Arb.string(1..60, Codepoint.alphanumeric())

        checkAll(100, promptArb) { prompt ->
            val result1 = PlanParser.inferPlanFromPrompt(prompt)
            val result2 = PlanParser.inferPlanFromPrompt(prompt)
            result1 shouldBe result2
        }
    }
})
