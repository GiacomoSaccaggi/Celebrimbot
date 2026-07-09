package com.github.giacomosaccaggi.celebrimbot.io

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The Keeper of the Shadow Vault — a java.nio implementation of ShadowLogOperator.
 *
 * Backups are stored under:
 *   <basePath>/.celebrimbot/shadow_log/<sessionId>/
 *
 * File naming: path separators are replaced with "__" so that
 * "src/main/Foo.kt" becomes "src__main__Foo.kt".
 * Deleted files get a ".DELETED" suffix so the undo logic can
 * distinguish "restore content" from "remove new file".
 *
 * At most [maxSessions] sessions are retained; older ones are pruned
 * automatically when endSession() is called.
 */
class HeadlessShadowLogOperator(
    private val basePath: String,
    private val maxSessions: Int = 10
) : ShadowLogOperator {

    private val gson = Gson()
    private val shadowRoot = File(basePath, ".celebrimbot/shadow_log")

    // Mutable state for the active session — guarded by @Synchronized methods
    @Volatile private var currentSessionId: String? = null
    private val pendingOps = mutableListOf<ShadowOperation>()

    // ── Session lifecycle ────────────────────────────────────────────

    @Synchronized
    override fun startSession(): String {
        val ts = LocalDateTime.now(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))
        val sessionId = "session_$ts"
        File(shadowRoot, sessionId).mkdirs()
        ensureGitIgnore()
        currentSessionId = sessionId
        pendingOps.clear()
        return sessionId
    }

    @Synchronized
    override fun endSession() {
        val sessionId = currentSessionId ?: return
        val manifest = ShadowManifest(
            sessionId = sessionId,
            createdAt = Instant.now().toString(),
            operations = pendingOps.toList()
        )
        File(shadowRoot, "$sessionId/manifest.json").writeText(gson.toJson(manifest))
        pendingOps.clear()
        currentSessionId = null
        pruneOldSessions()
    }

    // ── Backup operations ────────────────────────────────────────────

    @Synchronized
    override fun backupBeforeWrite(relativePath: String) {
        val source = File(basePath, relativePath)
        if (!source.exists()) {
            // File is new — record it so undo can delete it
            pendingOps.add(ShadowOperation("WRITE", relativePath, null, existed = false))
            return
        }
        if (source.length() > MAX_BACKUP_BYTES) {
            pendingOps.add(
                ShadowOperation("WRITE", relativePath, null, existed = true,
                    skipped = true, skipReason = "file too large (${source.length()} bytes)")
            )
            return
        }
        val backupName = encodePath(relativePath)
        val dest = File(shadowRoot, "${currentSessionId!!}/$backupName")
        Files.copy(source.toPath(), dest.toPath())
        pendingOps.add(ShadowOperation("WRITE", relativePath, backupName, existed = true))
    }

    @Synchronized
    override fun backupBeforeDelete(relativePath: String) {
        val source = File(basePath, relativePath)
        if (!source.exists()) {
            // Nothing to back up — record as a no-op delete
            pendingOps.add(ShadowOperation("DELETE", relativePath, null, existed = false))
            return
        }
        if (source.length() > MAX_BACKUP_BYTES) {
            pendingOps.add(
                ShadowOperation("DELETE", relativePath, null, existed = true,
                    skipped = true, skipReason = "file too large (${source.length()} bytes)")
            )
            return
        }
        val backupName = encodePath(relativePath) + ".DELETED"
        val dest = File(shadowRoot, "${currentSessionId!!}/$backupName")
        Files.copy(source.toPath(), dest.toPath())
        pendingOps.add(ShadowOperation("DELETE", relativePath, backupName, existed = true))
    }

    // ── Undo ─────────────────────────────────────────────────────────

    override fun undoLastSession(): UndoResult {
        val sessions = listSessions()
        if (sessions.isEmpty()) return UndoResult("none", emptyList(), emptyList(), emptyList(), listOf("No sessions found"))

        val latest = sessions.last()
        val sessionDir = File(shadowRoot, latest.sessionId)
        val manifest = loadManifest(sessionDir)
            ?: return UndoResult(latest.sessionId, emptyList(), emptyList(), emptyList(), listOf("Could not read manifest"))

        val restored = mutableListOf<String>()
        val deletedNew = mutableListOf<String>()
        val recreated = mutableListOf<String>()
        val errors = mutableListOf<String>()

        // Process in reverse order so dependent operations unwind correctly
        for (op in manifest.operations.asReversed()) {
            if (op.skipped) continue
            try {
                when (op.type) {
                    "WRITE" -> when {
                        op.existed && op.backupFile != null -> {
                            val backup = File(sessionDir, op.backupFile)
                            val target = File(basePath, op.path)
                            target.parentFile?.mkdirs()
                            Files.copy(backup.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            restored.add(op.path)
                        }
                        !op.existed -> {
                            File(basePath, op.path).delete()
                            deletedNew.add(op.path)
                        }
                    }
                    "DELETE" -> if (op.existed && op.backupFile != null) {
                        val backup = File(sessionDir, op.backupFile)
                        val target = File(basePath, op.path)
                        target.parentFile?.mkdirs()
                        Files.copy(backup.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        recreated.add(op.path)
                    }
                }
            } catch (e: Exception) {
                errors.add("${op.path}: ${e.message}")
            }
        }

        // Only remove the session directory if undo completed without errors
        if (errors.isEmpty()) sessionDir.deleteRecursively()

        return UndoResult(latest.sessionId, restored, deletedNew, recreated, errors)
    }

    // ── Session listing ──────────────────────────────────────────────

    override fun listSessions(): List<SessionSummary> {
        if (!shadowRoot.exists()) return emptyList()
        return shadowRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("session_") }
            ?.sortedBy { it.name }
            ?.mapNotNull { dir ->
                val manifest = loadManifest(dir) ?: return@mapNotNull null
                SessionSummary(manifest.sessionId, manifest.createdAt, manifest.operations.size)
            }
            ?: emptyList()
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Replaces path separators with "__" so the backup fits in a flat directory. */
    private fun encodePath(relativePath: String): String =
        relativePath.replace("/", "__").replace("\\", "__")

    private fun loadManifest(sessionDir: File): ShadowManifest? {
        val file = File(sessionDir, "manifest.json")
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), ShadowManifest::class.java)
        } catch (_: Exception) { null }
    }

    private fun pruneOldSessions() {
        val sessions = shadowRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("session_") }
            ?.sortedBy { it.name }
            ?: return
        if (sessions.size > maxSessions) {
            sessions.take(sessions.size - maxSessions).forEach { it.deleteRecursively() }
        }
    }

    /**
     * Ensures .celebrimbot/ is listed in the project's .gitignore.
     * Called once per session start so the shadow vault never pollutes git history.
     */
    private fun ensureGitIgnore() {
        val gitIgnore = File(basePath, ".gitignore")
        val entry = ".celebrimbot/"
        if (!gitIgnore.exists() || !gitIgnore.readText().contains(entry)) {
            gitIgnore.appendText("\n# Celebrimbot shadow log\n$entry\n")
        }
    }

    companion object {
        private const val MAX_BACKUP_BYTES = 1_048_576L // 1 MB
    }
}
