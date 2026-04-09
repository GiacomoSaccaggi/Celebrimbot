package com.github.giacomosaccaggi.celebrimbot.startup

import com.github.giacomosaccaggi.celebrimbot.services.CelebrimbotEmbeddedEngine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class CelebrimbotStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        
        if (!embeddedEngine.isModelDownloaded()) {
            embeddedEngine.downloadModel().thenAccept { success ->
                if (success) {
                    val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Celebrimbot Notifications")
                    notificationGroup?.createNotification(
                        "Celebrimbot",
                        "Local AI is ready! Qwen 2.5 is now forged and running.",
                        NotificationType.INFORMATION
                    )?.notify(project)
                    
                    // Warm-up after download
                    com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().execute {
                        try {
                            embeddedEngine.loadModel()
                        } catch (e: Exception) {
                            // Already logged in loadModel
                        }
                    }
                }
            }
        } else {
            // Warm-up if already downloaded
            com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().execute {
                try {
                    embeddedEngine.loadModel()
                } catch (e: Exception) {
                    // Already logged in loadModel
                }
            }
        }
    }
}
