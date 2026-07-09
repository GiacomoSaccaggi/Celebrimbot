package com.github.giacomosaccaggi.celebrimbot.startup

import com.github.giacomosaccaggi.celebrimbot.index.PalantirIndex
import com.github.giacomosaccaggi.celebrimbot.io.HeadlessProjectScanOperator
import com.github.giacomosaccaggi.celebrimbot.services.CelebrimbotEmbeddedEngine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil

class CelebrimbotStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (System.getProperty("robot-server.port") != null) return

        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)

        // Only download the model if not present — NO eager loading into RAM.
        // The model will be loaded lazily on first inference request via LazyModelManager.
        if (!embeddedEngine.isModelDownloaded()) {
            embeddedEngine.downloadModel().thenAccept { success ->
                if (success) {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Celebrimbot Notifications")
                        ?.createNotification(
                            "Celebrimbot",
                            "Local AI model downloaded! It will load on first use.",
                            NotificationType.INFORMATION
                        )?.notify(project)
                }
            }
        }

        // Palantír: refresh the semantic index in the background on project open.
        // If the index is missing or older than 1 hour, rebuild it.
        AppExecutorUtil.getAppExecutorService().execute {
            val basePath = project.basePath ?: return@execute
            val existing = PalantirIndex.loadOrNull(basePath)
            val oneHourMillis = 60 * 60 * 1_000L
            val needsRebuild = existing == null ||
                (System.currentTimeMillis() - existing.indexedAt) > oneHourMillis

            if (needsRebuild) {
                try {
                    val scanOp = HeadlessProjectScanOperator(basePath)
                    val updated = PalantirIndex.buildIncremental(basePath, scanOp, existing)
                    updated.save(basePath)
                } catch (_: Exception) {
                    // Index rebuild is best-effort; never crash the IDE startup
                }
            }
        }
    }
}
