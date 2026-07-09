package com.github.giacomosaccaggi.celebrimbot.index

import com.github.giacomosaccaggi.celebrimbot.io.ProjectScanOperator
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import kotlin.math.ln

// ── Data classes ─────────────────────────────────────────────────────────────

/**
 * A single entry in the Palantír — the distilled essence of one source file.
 *
 * [symbols] are un-lowercased identifiers (class names, function names) for
 * display in Elrond's prompt. [terms] is the lowercased term-frequency map
 * used for BM25 scoring.
 */
data class PalantirEntry(
    val path: String,
    val symbols: List<String>,
    val terms: Map<String, Int>,
    val lineCount: Int,
    val lastModified: Long          // epoch millis — used for staleness detection
)

/**
 * The Palantír — a complete BM25 index of the project.
 *
 * [idf] is precomputed at build time so query scoring is O(|query terms| × |entries|)
 * with no per-query log computations over the full vocabulary.
 */
data class PalantirIndex(
    val version: Int = CURRENT_VERSION,
    val basePath: String,
    val indexedAt: Long,
    val entries: List<PalantirEntry>,
    val idf: Map<String, Double>
) {
    // ── BM25 query ────────────────────────────────────────────────────────────

    /**
     * Scores every entry against [prompt] using BM25 and returns the top [topK]
     * results as [ScoredEntry] objects sorted by descending score.
     */
    fun query(prompt: String, topK: Int = 8): List<ScoredEntry> {
        val queryTerms = tokenize(prompt)
        if (queryTerms.isEmpty() || entries.isEmpty()) return emptyList()

        val avgDl = entries.map { it.terms.values.sum() }.average().takeIf { it > 0 } ?: 1.0

        return entries
            .map { entry ->
                val dl = entry.terms.values.sum().toDouble()
                val score = queryTerms.sumOf { term ->
                    val tf = entry.terms[term]?.toDouble() ?: 0.0
                    if (tf == 0.0) return@sumOf 0.0
                    val idfScore = idf[term] ?: 0.0
                    val numerator = tf * (BM25_K1 + 1.0)
                    val denominator = tf + BM25_K1 * (1.0 - BM25_B + BM25_B * dl / avgDl)
                    idfScore * (numerator / denominator)
                }
                ScoredEntry(entry, score)
            }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
            .take(topK)
    }

    // ── Staleness check ───────────────────────────────────────────────────────

    /**
     * Returns true if more than 20% of indexed files have a different lastModified
     * timestamp on disk, indicating the index is significantly out of date.
     */
    fun isStale(basePath: String): Boolean {
        if (entries.isEmpty()) return false
        val changedCount = entries.count { entry ->
            val file = File(basePath, entry.path)
            !file.exists() || file.lastModified() != entry.lastModified
        }
        return changedCount.toDouble() / entries.size > STALE_THRESHOLD
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    fun save(basePath: String) {
        val dir = File(basePath, INDEX_DIR)
        dir.mkdirs()
        File(dir, INDEX_FILE).writeText(GSON.toJson(this))
    }

    companion object {
        private const val CURRENT_VERSION = 1
        private const val INDEX_DIR = ".celebrimbot"
        private const val INDEX_FILE = "palantir_index.json"
        private const val STALE_THRESHOLD = 0.20   // 20% changed files → stale
        private const val BM25_K1 = 1.5
        private const val BM25_B = 0.75
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

        fun loadOrNull(basePath: String): PalantirIndex? {
            val file = File(basePath, "$INDEX_DIR/$INDEX_FILE")
            if (!file.exists()) return null
            return try {
                GSON.fromJson(file.readText(), PalantirIndex::class.java)
            } catch (_: Exception) { null }
        }

        // ── Index builder ─────────────────────────────────────────────────────

        /**
         * Builds a full index from scratch by walking all source files via
         * [scanOperator]. Computes per-file term frequencies and global IDF scores.
         */
        fun build(basePath: String, scanOperator: ProjectScanOperator): PalantirIndex {
            val sourcePaths = scanOperator.walkSourceFiles()
            val entries = sourcePaths.mapNotNull { relativePath ->
                indexFile(basePath, relativePath)
            }
            val idf = computeIdf(entries)
            return PalantirIndex(
                basePath = basePath,
                indexedAt = System.currentTimeMillis(),
                entries = entries,
                idf = idf
            )
        }

        /**
         * Rebuilds only the entries whose file has changed since the last index,
         * then recomputes IDF globally. Falls back to a full build if [existing]
         * is null.
         */
        fun buildIncremental(
            basePath: String,
            scanOperator: ProjectScanOperator,
            existing: PalantirIndex?
        ): PalantirIndex {
            if (existing == null) return build(basePath, scanOperator)

            val existingByPath = existing.entries.associateBy { it.path }
            val sourcePaths = scanOperator.walkSourceFiles()

            val entries = sourcePaths.mapNotNull { relativePath ->
                val file = File(basePath, relativePath)
                val cached = existingByPath[relativePath]
                if (cached != null && file.lastModified() == cached.lastModified) {
                    cached   // unchanged — reuse
                } else {
                    indexFile(basePath, relativePath)   // changed or new — re-tokenize
                }
            }
            return PalantirIndex(
                basePath = basePath,
                indexedAt = System.currentTimeMillis(),
                entries = entries,
                idf = computeIdf(entries)
            )
        }

        // ── Tokenization ──────────────────────────────────────────────────────

        private fun indexFile(basePath: String, relativePath: String): PalantirEntry? {
            val file = File(basePath, relativePath)
            if (!file.exists() || !file.isFile) return null
            return try {
                val content = file.readText(Charsets.UTF_8)
                val symbols = extractSymbols(content, relativePath)
                val terms = buildTermFrequency(content)
                PalantirEntry(
                    path = relativePath,
                    symbols = symbols,
                    terms = terms,
                    lineCount = content.lines().size,
                    lastModified = file.lastModified()
                )
            } catch (_: Exception) { null }
        }

        /**
         * Extracts named identifiers (classes, functions, top-level declarations)
         * from source content using language-specific regex patterns.
         * Results are un-lowercased for display purposes.
         */
        private fun extractSymbols(content: String, path: String): List<String> {
            val ext = path.substringAfterLast('.', "")
            val pattern = when (ext) {
                "kt", "kts", "java", "scala", "cs" ->
                    Regex("""(?:class|interface|object|fun|val|var|enum)\s+(\w+)""")
                "py" ->
                    Regex("""(?:class|def)\s+(\w+)""")
                "js", "ts", "jsx", "tsx" ->
                    Regex("""(?:function|class|const|let|var)\s+(\w+)""")
                "go" ->
                    Regex("""(?:func|type|var|const)\s+(\w+)""")
                "rs" ->
                    Regex("""(?:fn|struct|enum|trait|impl|mod|const|let)\s+(\w+)""")
                else ->
                    Regex("""(?:class|function|def|func|fn)\s+(\w+)""")
            }
            return pattern.findAll(content)
                .map { it.groupValues[1] }
                .filter { it.length >= 2 && it !in STOPWORDS }
                .distinct()
                .take(50)   // cap per file to avoid noise from generated code
                .toList()
        }

        /**
         * Tokenizes [text] into a list of terms.
         * Splits on non-word characters, lowercases, filters stopwords and
         * very short tokens.
         */
        internal fun tokenize(text: String): List<String> =
            text.split(Regex("""\W+"""))
                .map { it.lowercase() }
                .filter { it.length >= 3 && it !in STOPWORDS }

        private fun buildTermFrequency(content: String): Map<String, Int> {
            val freq = mutableMapOf<String, Int>()
            tokenize(content).forEach { term -> freq[term] = (freq[term] ?: 0) + 1 }
            return freq
        }

        // ── IDF computation ───────────────────────────────────────────────────

        private fun computeIdf(entries: List<PalantirEntry>): Map<String, Double> {
            val n = entries.size.toDouble()
            if (n == 0.0) return emptyMap()

            // Count how many documents contain each term
            val docFreq = mutableMapOf<String, Int>()
            entries.forEach { entry ->
                entry.terms.keys.forEach { term ->
                    docFreq[term] = (docFreq[term] ?: 0) + 1
                }
            }

            // IDF = log((N - df + 0.5) / (df + 0.5) + 1)  — Robertson-Sparck Jones variant
            return docFreq.mapValues { (_, df) ->
                ln((n - df + 0.5) / (df + 0.5) + 1.0)
            }
        }

        // ── Stopwords ─────────────────────────────────────────────────────────

        private val STOPWORDS = setOf(
            // Kotlin / Java / general OOP
            "val", "var", "fun", "class", "object", "interface", "enum", "data",
            "return", "import", "package", "public", "private", "protected",
            "override", "open", "final", "static", "abstract", "sealed",
            "this", "super", "null", "true", "false", "new", "void",
            "int", "long", "float", "double", "boolean", "string", "char",
            "if", "else", "when", "for", "while", "do", "try", "catch",
            "throw", "throws", "finally", "break", "continue",
            // Python
            "def", "self", "none", "pass", "with", "from", "not", "and", "or",
            "lambda", "yield", "async", "await",
            // JS / TS
            "const", "let", "function", "typeof", "instanceof", "undefined",
            "prototype", "require", "module", "exports",
            // Generic noise
            "the", "and", "for", "are", "but", "not", "you", "all", "can",
            "get", "set", "add", "put", "has", "use", "new", "any", "map",
            "list", "type", "name", "size", "init", "run", "log", "err"
        )
    }
}

/** A query result pairing an entry with its BM25 relevance score. */
data class ScoredEntry(val entry: PalantirEntry, val score: Double)
