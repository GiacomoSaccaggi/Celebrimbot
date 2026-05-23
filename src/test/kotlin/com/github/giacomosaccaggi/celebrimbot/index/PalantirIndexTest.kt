package com.github.giacomosaccaggi.celebrimbot.index

import org.junit.Test
import org.junit.Assert.*

class PalantirIndexTest {

    private fun buildIndex(
        entries: List<PalantirEntry> = listOf(
            PalantirEntry(
                path = "src/Foo.kt",
                symbols = listOf("Foo", "bar"),
                terms = mapOf("foo" to 3, "bar" to 1, "kotlin" to 2),
                lineCount = 50,
                lastModified = System.currentTimeMillis()
            ),
            PalantirEntry(
                path = "src/Baz.kt",
                symbols = listOf("Baz"),
                terms = mapOf("baz" to 5, "kotlin" to 1, "service" to 3),
                lineCount = 80,
                lastModified = System.currentTimeMillis()
            )
        ),
        idf: Map<String, Double> = mapOf(
            "foo" to 1.5,
            "bar" to 2.0,
            "kotlin" to 1.0,
            "baz" to 1.8,
            "service" to 1.2
        )
    ) = PalantirIndex(
        basePath = "/tmp",
        indexedAt = System.currentTimeMillis(),
        entries = entries,
        idf = idf
    )

    @Test
    fun `query returns results with score greater than zero sorted descending`() {
        val index = buildIndex()
        val results = index.query("kotlin code")
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.score > 0.0 })
        for (i in 0 until results.size - 1) {
            assertTrue(results[i].score >= results[i + 1].score)
        }
    }

    @Test
    fun `query with empty prompt returns empty list`() {
        val index = buildIndex()
        val results = index.query("")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `query respects topK limit`() {
        val index = buildIndex()
        val results = index.query("kotlin", topK = 1)
        assertEquals(1, results.size)
    }

    @Test
    fun `query with empty entries returns empty list`() {
        val index = buildIndex(entries = emptyList())
        val results = index.query("kotlin")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `tokenize splits text lowercases and filters stopwords and short tokens`() {
        val tokens = PalantirIndex.tokenize("Hello World the FOO is bar")
        // "the" is a stopword, "is" is too short (2 chars)
        assertTrue("hello" in tokens)
        assertTrue("world" in tokens)
        assertTrue("foo" in tokens)
        assertTrue("bar" in tokens)
        assertFalse("the" in tokens)
        assertFalse("is" in tokens)
        // All tokens are lowercase
        assertTrue(tokens.all { it == it.lowercase() })
        // All tokens have length >= 3
        assertTrue(tokens.all { it.length >= 3 })
    }
}
