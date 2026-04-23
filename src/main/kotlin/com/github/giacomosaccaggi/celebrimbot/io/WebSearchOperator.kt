package com.github.giacomosaccaggi.celebrimbot.io

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface WebSearchOperator {
    fun search(query: String): String
    fun fetchPage(url: String): String
}

class DuckDuckGoSearchOperator : WebSearchOperator {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun search(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "Celebrimbot/1.0")
            .GET()
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            parseDuckDuckGoResponse(response.body())
        } catch (e: Exception) {
            "Error: web search failed — ${e.message}"
        }
    }

    override fun fetchPage(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Celebrimbot/1.0")
            .GET()
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val body = response.body()
            stripHtml(body).take(4000)
        } catch (e: Exception) {
            "Error: fetch failed — ${e.message}"
        }
    }

    private fun parseDuckDuckGoResponse(json: String): String {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            val sb = StringBuilder()

            val abstract = obj.get("AbstractText")?.asString?.takeIf { it.isNotBlank() }
            if (abstract != null) sb.appendLine("Summary: $abstract")

            val abstractUrl = obj.get("AbstractURL")?.asString?.takeIf { it.isNotBlank() }
            if (abstractUrl != null) sb.appendLine("Source: $abstractUrl")

            val results = obj.getAsJsonArray("RelatedTopics")
            var count = 0
            results?.forEach { el ->
                if (count >= 5) return@forEach
                val topic = el.asJsonObject
                val text = topic.get("Text")?.asString?.takeIf { it.isNotBlank() } ?: return@forEach
                val url = topic.getAsJsonObject("FirstURL")?.asString ?: ""
                sb.appendLine("- $text${if (url.isNotBlank()) " ($url)" else ""}")
                count++
            }

            if (sb.isEmpty()) "No results found for the query." else sb.toString().trim()
        } catch (e: Exception) {
            "Error: could not parse search response — ${e.message}"
        }
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
}
