package com.github.giacomosaccaggi.celebrimbot.index

import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Property-based tests for PalantirIndex BM25 scoring.
 *
 * **Validates: Requirements 8.6, 8.7**
 */
class PalantirIndexPropertyTest : FunSpec({

    // -- Generators --

    /** Generates a random term (3+ alphanumeric chars, not in stopwords). */
    val termArb: Arb<String> = Arb.string(3..8, Codepoint.az()).map { it.lowercase() }
        .filter { it !in STOPWORDS }

    /** Generates a PalantirEntry with random terms and frequencies. */
    val entryArb: Arb<PalantirEntry> = arbitrary {
        val numTerms = Arb.int(1..6).bind()
        val terms = (1..numTerms).associate {
            termArb.bind() to Arb.int(1..10).bind()
        }
        PalantirEntry(
            path = "src/${Arb.string(3..8, Codepoint.az()).bind()}.kt",
            symbols = listOf(Arb.string(3..8, Codepoint.az()).bind()),
            terms = terms,
            lineCount = Arb.int(10..200).bind(),
            lastModified = System.currentTimeMillis()
        )
    }

    /** Generates a PalantirIndex with 1-5 entries and computed IDF. */
    val indexArb: Arb<PalantirIndex> = arbitrary {
        val numEntries = Arb.int(1..5).bind()
        val entries = (1..numEntries).map { entryArb.bind() }
        val n = entries.size.toDouble()
        val docFreq = mutableMapOf<String, Int>()
        entries.forEach { entry ->
            entry.terms.keys.forEach { term ->
                docFreq[term] = (docFreq[term] ?: 0) + 1
            }
        }
        val idf = docFreq.mapValues { (_, df) ->
            kotlin.math.ln((n - df + 0.5) / (df + 0.5) + 1.0)
        }
        PalantirIndex(
            basePath = "/tmp",
            indexedAt = System.currentTimeMillis(),
            entries = entries,
            idf = idf
        )
    }

    /** Generates a non-empty query string from alphanumeric words. */
    val queryArb: Arb<String> = arbitrary {
        val numWords = Arb.int(1..4).bind()
        (1..numWords).map { Arb.string(3..10, Codepoint.az()).bind() }.joinToString(" ")
    }

    // -------------------------------------------------------------------------
    // Property 6: BM25 scores are non-negative
    // **Validates: Requirement 8.6**
    // -------------------------------------------------------------------------
    test("Property 6: BM25 scores are strictly positive for all returned results") {
        checkAll(100, indexArb, queryArb) { index, query ->
            val results = index.query(query)
            results.forEach { scored ->
                assert(scored.score > 0.0) {
                    "Expected score > 0 but got ${scored.score} for entry ${scored.entry.path} with query '$query'"
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 7: BM25 empty query returns empty results
    // **Validates: Requirement 8.7**
    // -------------------------------------------------------------------------
    test("Property 7: BM25 empty query returns empty results") {
        // Common English stopwords that are also in PalantirIndex.STOPWORDS
        val stopwordsOnly = listOf("the", "and", "for", "are", "but", "not", "you", "all", "can")

        val emptyQueryArb: Arb<String> = arbitrary {
            val choice = Arb.int(0..2).bind()
            when (choice) {
                0 -> ""
                1 -> "   "
                else -> {
                    val count = Arb.int(1..3).bind()
                    (1..count).map { Arb.element(stopwordsOnly).bind() }.joinToString(" ")
                }
            }
        }

        checkAll(100, indexArb, emptyQueryArb) { index, query ->
            val results = index.query(query)
            results.shouldBeEmpty()
        }
    }
}) {
    companion object {
        /** Mirror of PalantirIndex stopwords for the generator filter. */
        val STOPWORDS = setOf(
            "val", "var", "fun", "class", "object", "interface", "enum", "data",
            "return", "import", "package", "public", "private", "protected",
            "override", "open", "final", "static", "abstract", "sealed",
            "this", "super", "null", "true", "false", "new", "void",
            "int", "long", "float", "double", "boolean", "string", "char",
            "if", "else", "when", "for", "while", "do", "try", "catch",
            "throw", "throws", "finally", "break", "continue",
            "def", "self", "none", "pass", "with", "from", "not", "and", "or",
            "lambda", "yield", "async", "await",
            "const", "let", "function", "typeof", "instanceof", "undefined",
            "prototype", "require", "module", "exports",
            "the", "and", "for", "are", "but", "not", "you", "all", "can",
            "get", "set", "add", "put", "has", "use", "new", "any", "map",
            "list", "type", "name", "size", "init", "run", "log", "err"
        )
    }
}
