package com.github.giacomosaccaggi.celebrimbot.io

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File

/**
 * IntelliJ implementation of FileOperator.
 * Uses PSI, VFS and Document APIs to ensure IDE synchronization.
 */
class IdeFileOperator(private val project: Project) : FileOperator {

    override fun readFile(path: String): String {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(File(getProjectBasePath(), path).path)
            ?: return "Error: File not found in IDE: $path"
        return com.intellij.openapi.application.ReadAction.compute<String, Exception> {
            FileDocumentManager.getInstance().getDocument(virtualFile)?.text ?: VfsUtil.loadText(virtualFile)
        }
    }

    override fun writeFile(path: String, content: String): Boolean {
        return try {
            WriteCommandAction.runWriteCommandAction(project) {
                val fullPath = File(getProjectBasePath(), path).path
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
                    ?: run {
                        val file = File(fullPath)
                        file.parentFile.mkdirs()
                        file.createNewFile()
                        LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
                    }

                if (virtualFile != null) {
                    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                    if (document != null) {
                        document.setText(content)
                        FileDocumentManager.getInstance().saveDocument(document)
                    } else {
                        VfsUtil.saveText(virtualFile, content)
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun deleteFile(path: String): Boolean {
        return try {
            WriteCommandAction.runWriteCommandAction(project) {
                val fullPath = File(getProjectBasePath(), path).path
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
                virtualFile?.delete(this)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun resolvePath(fileName: String): String? {
        val files = FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.projectScope(project))
        return files.firstOrNull()?.virtualFile?.path?.let { 
            val base = getProjectBasePath()
            if (base != null && it.startsWith(base)) {
                it.removePrefix(base).removePrefix("/")
            } else {
                it
            }
        }
    }

    override fun getProjectBasePath(): String? = project.basePath
}
