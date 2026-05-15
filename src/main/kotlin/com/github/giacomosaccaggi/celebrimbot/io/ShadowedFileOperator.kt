package com.github.giacomosaccaggi.celebrimbot.io

/**
 * The One Decorator to rule them all.
 *
 * ShadowedFileOperator wraps any FileOperator and intercepts every
 * write and delete, recording a backup in the Shadow Log before
 * delegating the actual operation to the underlying operator.
 *
 * Zero changes are required to IdeFileOperator or HeadlessFileOperator —
 * the decorator is transparent to all callers.
 */
class ShadowedFileOperator(
    private val delegate: FileOperator,
    private val shadowLog: ShadowLogOperator
) : FileOperator {

    override fun readFile(path: String): String =
        delegate.readFile(path)

    override fun writeFile(path: String, content: String): Boolean {
        shadowLog.backupBeforeWrite(path)
        return delegate.writeFile(path, content)
    }

    override fun deleteFile(path: String): Boolean {
        shadowLog.backupBeforeDelete(path)
        return delegate.deleteFile(path)
    }

    override fun resolvePath(fileName: String): String? =
        delegate.resolvePath(fileName)

    override fun getProjectBasePath(): String? =
        delegate.getProjectBasePath()
}
