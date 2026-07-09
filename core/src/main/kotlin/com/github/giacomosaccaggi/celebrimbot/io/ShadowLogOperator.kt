package com.github.giacomosaccaggi.celebrimbot.io

/**
 * The Shadow Log — a record of every deed performed upon the scrolls,
 * kept in the Unseen World so that what was wrought may be unwrought.
 *
 * Before any write or deletion, the original scroll is copied to the
 * shadow vault. A manifest is sealed at the end of each session so
 * that the Undo command may restore the realm to its former state.
 */
interface ShadowLogOperator {
    /** Opens a new session in the shadow vault. Returns the session ID. */
    fun startSession(): String

    /** Copies the current file to the shadow vault before it is overwritten. */
    fun backupBeforeWrite(relativePath: String)

    /** Copies the current file to the shadow vault before it is deleted, marking it as DELETED. */
    fun backupBeforeDelete(relativePath: String)

    /** Seals the session manifest and prunes sessions beyond the retention limit. */
    fun endSession()

    /** Restores the realm to the state before the most recent session. */
    fun undoLastSession(): UndoResult

    /** Lists all sessions currently held in the shadow vault. */
    fun listSessions(): List<SessionSummary>
}

/** A single operation recorded in the shadow manifest. */
data class ShadowOperation(
    val type: String,           // "WRITE" or "DELETE"
    val path: String,           // relative path of the affected scroll
    val backupFile: String?,    // name of the backup file in the session dir, null if file was new
    val existed: Boolean,       // whether the file existed before the operation
    val skipped: Boolean = false,
    val skipReason: String? = null
)

/** The sealed record of a completed session. */
data class ShadowManifest(
    val sessionId: String,
    val createdAt: String,
    val operations: List<ShadowOperation>
)

/** The outcome of an undo operation. */
data class UndoResult(
    val sessionId: String,
    val restoredFiles: List<String>,      // files that existed before and were restored
    val deletedNewFiles: List<String>,    // files that were newly created and have been removed
    val recreatedFiles: List<String>,     // files that were deleted and have been brought back
    val errors: List<String>
)

/** A brief summary of a session for display in the CLI. */
data class SessionSummary(
    val sessionId: String,
    val createdAt: String,
    val operationCount: Int
)
