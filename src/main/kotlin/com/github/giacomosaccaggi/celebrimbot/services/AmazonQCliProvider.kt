package com.github.giacomosaccaggi.celebrimbot.services

import com.github.giacomosaccaggi.celebrimbot.settings.AmazonQSettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Provides access to Amazon Q Developer using the SSO token already written
 * by the Amazon Q JetBrains plugin or VSCode extension to ~/.aws/sso/cache/.
 *
 * No separate CLI or login is needed if the user is already authenticated
 * via the Amazon Q plugin. The token is read directly from the cache.
 *
 * API used: Amazon Q / CodeWhisperer SendMessage (codewhisperer:conversations scope).
 * Endpoint: https://codewhisperer.us-east-1.amazonaws.com/
 */
@Service(Service.Level.PROJECT)
class AmazonQCliProvider(private val project: Project) {

    private val logger = Logger.getInstance(AmazonQCliProvider::class.java)
    private val gson = Gson()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    // ── SSO token discovery ───────────────────────────────────────────────────

    /**
     * Reads the best available SSO access token from ~/.aws/sso/cache/.
     * Matches on both "startUrl" and "issuerUrl" fields (different clients use different keys).
     * Requires codewhisperer:conversations scope.
     * Returns the token with the latest expiry that is still valid.
     */
    fun readSsoToken(): SsoToken? {
        val cacheDir = File(System.getProperty("user.home"), ".aws/sso/cache")
        if (!cacheDir.isDirectory) return null
        val settings = AmazonQSettings.getInstance(project).state
        val configuredUrl = settings.ssoStartUrl.trimEnd('/')

        return cacheDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                    val obj = gson.fromJson(file.readText(), JsonObject::class.java)
                        ?: return@runCatching null

                    // Match start URL against both field names used by different clients
                    val startUrl = (obj.get("startUrl") ?: obj.get("issuerUrl"))?.asString ?: return@runCatching null
                    if (configuredUrl.isNotBlank() && !startUrl.trimEnd('/').contains(configuredUrl, ignoreCase = true)
                        && !configuredUrl.contains(startUrl.trimEnd('/'), ignoreCase = true)) return@runCatching null

                    val accessToken = obj.get("accessToken")?.asString ?: return@runCatching null
                    val expiresAtStr = obj.get("expiresAt")?.asString ?: return@runCatching null
                    val expiry = Instant.parse(expiresAtStr)
                    // Reject tokens expiring in less than 60 seconds
                    if (expiry.isBefore(Instant.now().plusSeconds(60))) return@runCatching null

                    val scopes = obj.getAsJsonArray("scopes")
                        ?.map { it.asString } ?: emptyList()

                    SsoToken(accessToken, expiry, startUrl, scopes)
                }.getOrNull()
            }
            // Prefer tokens with conversations scope, then pick latest expiry
            ?.sortedWith(compareByDescending<SsoToken> {
                it.scopes.contains("codewhisperer:conversations")
            }.thenByDescending { it.expiresAt })
            ?.firstOrNull()
    }

    fun isAuthenticated(): Boolean = readSsoToken() != null

    fun isInstalled(): Boolean = true // always "installed" — reads from cache, no CLI needed

    // ── Kiro-specific token discovery ─────────────────────────────────────────

    /**
     * Reads the Kiro auth token directly from the two well-known files Kiro
     * writes to ~/.aws/sso/cache/:
     *   - kiro-auth-token.json      (IDE session)
     *   - kiro-auth-token-cli.json  (CLI session)
     * Both contain a valid `accessToken` field.
     */
    fun readKiroToken(): SsoToken? {
        val cacheDir = File(System.getProperty("user.home"), ".aws/sso/cache")
        val kiroFiles = listOf("kiro-auth-token.json", "kiro-auth-token-cli.json")
        return kiroFiles
            .mapNotNull { name ->
                runCatching {
                    val obj = gson.fromJson(File(cacheDir, name).readText(), JsonObject::class.java)
                        ?: return@runCatching null
                    val accessToken = obj.get("accessToken")?.asString ?: return@runCatching null
                    val expiresAtStr = obj.get("expiresAt")?.asString ?: return@runCatching null
                    val expiry = Instant.parse(expiresAtStr)
                    if (expiry.isBefore(Instant.now().plusSeconds(60))) return@runCatching null
                    val startUrl = obj.get("startUrl")?.asString ?: ""
                    val scopes = obj.getAsJsonArray("scopes")
                        ?.map { it.asString } ?: listOf("codewhisperer:conversations")
                    SsoToken(accessToken, expiry, startUrl, scopes)
                }.getOrNull()
            }
            .maxByOrNull { it.expiresAt }
    }

    fun isKiroAuthenticated(): Boolean = readKiroToken() != null

    /**
     * Launches Kiro login. Tries `kiro login` CLI first; if not found,
     * opens the Kiro website so the user can log in and restart Kiro.
     */
    fun loginWithKiro(onComplete: (Boolean) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (isKiroAuthenticated()) {
                ApplicationManager.getApplication().invokeLater { onComplete(true) }
                return@executeOnPooledThread
            }
            // Try kiro CLI login
            val success = runCatching {
                val proc = ProcessBuilder("kiro", "login")
                    .inheritIO()
                    .start()
                proc.waitFor(180, TimeUnit.SECONDS) && proc.exitValue() == 0
            }.getOrDefault(false)
            if (!success) {
                // Fallback: open Kiro download page so user can install/login
                runCatching {
                    java.awt.Desktop.getDesktop().browse(URI.create("https://kiro.dev"))
                }
            }
            ApplicationManager.getApplication().invokeLater { onComplete(success) }
        }
    }

    fun askAsKiro(prompt: String, persona: String): String {
        val token = readKiroToken()
            ?: return "Error: Kiro not authenticated — open Kiro IDE and log in first."
        val settings = AmazonQSettings.getInstance(project).state
        val safePrompt = if (settings.redactSecrets) redactSecrets(prompt) else prompt
        return runCatching {
            sendCodeWhispererMessage(token.accessToken, safePrompt, persona, settings)
        }.getOrElse { e ->
            logger.warn("Kiro request failed: ${e.message}")
            "Error: ${e.message}"
        }
    }

    // ── Login (only needed if token is missing/expired) ───────────────────────

    /**
     * Opens browser login via `aws sso login` using the sso-session already
     * configured in ~/.aws/config by the Amazon Q plugin (session name "nlsn").
     * If no session is found, falls back to writing a minimal one.
     */
    fun loginWithBrowser(onComplete: (Boolean) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            // First check if already authenticated
            if (isAuthenticated()) {
                ApplicationManager.getApplication().invokeLater { onComplete(true) }
                return@executeOnPooledThread
            }

            val sessionName = detectExistingSsoSessionName()
                ?: run {
                    // Write a minimal session block using configured values
                    val settings = AmazonQSettings.getInstance(project).state
                    if (settings.ssoStartUrl.isNotBlank()) {
                        ensureSsoSessionConfigured(settings.ssoStartUrl, settings.ssoRegion)
                        "celebrimbot-sso"
                    } else {
                        ApplicationManager.getApplication().invokeLater { onComplete(false) }
                        return@executeOnPooledThread
                    }
                }

            val success = runCatching {
                val proc = ProcessBuilder("aws", "sso", "login", "--sso-session", sessionName)
                    .inheritIO()
                    .start()
                proc.waitFor(180, TimeUnit.SECONDS) && proc.exitValue() == 0
            }.getOrDefault(false)

            ApplicationManager.getApplication().invokeLater { onComplete(success) }
        }
    }

    /** Reads the first sso-session name from ~/.aws/config. */
    private fun detectExistingSsoSessionName(): String? {
        val config = File(System.getProperty("user.home"), ".aws/config")
        if (!config.exists()) return null
        return config.readLines()
            .firstOrNull { it.trim().startsWith("[sso-session ") }
            ?.trim()
            ?.removePrefix("[sso-session ")
            ?.removeSuffix("]")
    }

    private fun ensureSsoSessionConfigured(startUrl: String, region: String) {
        val configFile = File(System.getProperty("user.home"), ".aws/config")
        val existing = if (configFile.exists()) configFile.readText() else ""
        if (!existing.contains("[sso-session celebrimbot-sso]")) {
            configFile.parentFile.mkdirs()
            configFile.appendText("""
                |
                |[sso-session celebrimbot-sso]
                |sso_start_url = $startUrl
                |sso_region = $region
                |sso_registration_scopes = codewhisperer:conversations,codewhisperer:completions
            """.trimMargin())
        }
    }

    // ── Auto-detect SSO config from ~/.aws/config ─────────────────────────────

    /**
     * Reads the SSO start URL and region from the first sso-session block in
     * ~/.aws/config. Called by Settings UI to pre-fill fields without hardcoding.
     */
    fun detectSsoConfigFromAwsConfig(): Pair<String, String>? {
        val config = File(System.getProperty("user.home"), ".aws/config")
        if (!config.exists()) return null
        val lines = config.readLines()
        var inSsoSession = false
        var startUrl = ""
        var region = ""
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[sso-session ")) { inSsoSession = true; continue }
            if (trimmed.startsWith("[") && inSsoSession) break
            if (inSsoSession) {
                when {
                    trimmed.startsWith("sso_start_url") -> startUrl = trimmed.substringAfter("=").trim()
                    trimmed.startsWith("sso_region") -> region = trimmed.substringAfter("=").trim()
                }
            }
        }
        return if (startUrl.isNotBlank()) Pair(startUrl, region) else null
    }

    // ── Inference via CodeWhisperer API ───────────────────────────────────────

    fun ask(prompt: String, persona: String): String {
        val token = readSsoToken()
            ?: return "Error: Amazon Q not authenticated."

        val settings = AmazonQSettings.getInstance(project).state
        val safePrompt = if (settings.redactSecrets) redactSecrets(prompt) else prompt

        return runCatching {
            sendCodeWhispererMessage(token.accessToken, safePrompt, persona, settings)
        }.getOrElse { e ->
            logger.warn("Amazon Q request failed: ${e.message}")
            "Error: ${e.message}"
        }
    }

    /**
     * Calls the CodeWhisperer SendMessage API (chat/conversations endpoint).
     * Uses the bearer token directly — no SigV4 signing needed for this API.
     */
    private fun sendCodeWhispererMessage(
        accessToken: String,
        prompt: String,
        persona: String,
        settings: AmazonQSettings.State
    ): String {
        val region = settings.ssoRegion.ifBlank { "us-east-1" }
        val endpoint = "https://codewhisperer.$region.amazonaws.com/"

        val conversationId = UUID.randomUUID().toString()
        val body = gson.toJson(mapOf(
            "conversationState" to mapOf(
                "conversationId" to conversationId,
                "chatTriggerType" to "MANUAL",
                "currentMessage" to mapOf(
                    "userInputMessage" to mapOf(
                        "content" to "$persona\n\n$prompt",
                        "userInputMessageContext" to emptyMap<String, Any>()
                    )
                )
            )
        ))

        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/x-amz-json-1.0")
            .header("X-Amz-Target", "AmazonCodeWhispererStreamingService.GenerateAssistantResponse")
            .header("Authorization", "Bearer $accessToken")
            .timeout(Duration.ofMillis(settings.timeoutMillis))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return "Error: Amazon Q token expired or unauthorized (HTTP ${response.statusCode()}). Please login again."
        }
        if (response.statusCode() != 200) {
            val body = response.body().take(500)
            logger.warn("CodeWhisperer API ${response.statusCode()}: $body")
            return "Error: Amazon Q API returned HTTP ${response.statusCode()}: $body"
        }

        val rawBody = response.body()
        logger.warn("CodeWhisperer raw (first 500): ${rawBody.take(500).replace("\n", "\\n")}")
        val parsed = parseCodeWhispererResponse(rawBody)
        return if (parsed.startsWith("Error:")) "$parsed | raw: ${rawBody.take(300)}" else parsed
    }

    private fun parseCodeWhispererResponse(raw: String): String {
        return runCatching {
            // The response is an AWS event stream — binary frames with JSON payloads embedded.
            // We extract all JSON objects from the raw text by scanning for known field names.
            val chunks = mutableListOf<String>()

            // Strategy 1: find all JSON objects containing assistantResponseEvent
            val regex = Regex("\\{[^{}]*\"assistantResponseEvent\"[^{}]*\\}")
            regex.findAll(raw).forEach { match ->
                runCatching {
                    val obj = gson.fromJson(match.value, JsonObject::class.java)
                    obj.getAsJsonObject("assistantResponseEvent")?.get("content")?.asString
                        ?.let { chunks.add(it) }
                }.getOrNull()
            }
            if (chunks.isNotEmpty()) return chunks.joinToString("")

            // Strategy 2: scan each line as a JSON object
            raw.lines().filter { it.trimStart().startsWith("{") }.forEach { line ->
                runCatching {
                    val obj = gson.fromJson(line.trim(), JsonObject::class.java)
                    (obj.get("content")
                        ?: obj.getAsJsonObject("assistantResponseEvent")?.get("content")
                        ?: obj.getAsJsonObject("message")?.get("content"))
                        ?.asString?.let { chunks.add(it) }
                }.getOrNull()
            }
            if (chunks.isNotEmpty()) return chunks.joinToString("")

            // Strategy 3: extract any quoted string after "content":
            val contentRegex = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            contentRegex.findAll(raw).forEach { match ->
                chunks.add(match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t"))
            }
            if (chunks.isNotEmpty()) return chunks.joinToString("")

            "Error: unexpected Amazon Q response format"
        }.getOrElse { "Error parsing Amazon Q response: ${it.message}" }
    }

    // ── Secret redaction ──────────────────────────────────────────────────────

    private val secretPatterns = listOf(
        Regex("""(?i)(aws_access_key_id|aws_secret_access_key|aws_session_token)\s*[=:]\s*\S+"""),
        Regex("""(?i)(api[_\-]?key|token|secret|password|passwd)\s*[=:]\s*['"]?\S+"""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""")
    )

    private fun redactSecrets(input: String): String {
        var result = input
        for (p in secretPatterns) {
            result = result.replace(p) { m ->
                "${m.value.substringBefore("=").substringBefore(":").trim()}=<REDACTED>"
            }
        }
        return result
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    data class SsoToken(
        val accessToken: String,
        val expiresAt: Instant,
        val startUrl: String,
        val scopes: List<String>
    )

    companion object {
        fun getInstance(project: Project): AmazonQCliProvider = project.service()
    }
}
