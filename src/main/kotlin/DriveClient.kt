package io.github.jvmusin

import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class DriveClient(private val service: Drive) {
    private val logger = getLogger()

    data class Backoff(val startPeriod: Duration, val maxPeriod: Duration)

    private fun <T> withBackoff(
        backoff: Backoff = Backoff(startPeriod = 5.seconds, maxPeriod = 30.minutes),
        operation: Drive.() -> T
    ): T {
        val start = System.currentTimeMillis()
        var sleepPeriod = backoff.startPeriod
        while (true) {
            try {
                return service.operation()
            } catch (e: Exception) {
                val timeSpentMillis = System.currentTimeMillis() + sleepPeriod.inWholeMilliseconds - start
                if (timeSpentMillis.milliseconds > backoff.maxPeriod) throw e
                logger.warning("Operation failed, sleeping for $sleepPeriod. Reason: ${e.message}")
                Thread.sleep(sleepPeriod.toJavaDuration())
                sleepPeriod = minOf(sleepPeriod * 2, backoff.maxPeriod)
            }
        }
    }

    fun getOrCreateFolder(name: String, parent: String?): String {
        val folderMimeType = "application/vnd.google-apps.folder"
        val acceptableFolders = withBackoff {
            files().list().execute().files.filter {
                it.name == name && it.mimeType == folderMimeType
            }
        }
        require(acceptableFolders.size < 2) {
            "Multiple folders named '$name' found"
        }
        if (acceptableFolders.size == 1) return acceptableFolders[0].id
        val content = com.google.api.services.drive.model.File().apply {
            this.name = name
            this.mimeType = folderMimeType
            this.parents = listOfNotNull(parent)
        }
        return withBackoff { files().create(content).setFields("id").execute().id }
    }

    fun createFile(parent: String, name: String, sourceFile: Path): String {
        val mimeType = when (sourceFile.extension) {
            "pdf" -> "application/pdf"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            else -> error("Unsupported extension for $sourceFile")
        }
        val content = com.google.api.services.drive.model.File().apply {
            this.name = name
            this.parents = listOf(parent)
        }
        val mediaContent = FileContent(mimeType, sourceFile.toFile())
        return withBackoff { files().create(content, mediaContent).setFields("id").execute().id }
    }

    fun updateFile(parent: String, fileId: String, sourceFile: Path): String {
        val mimeType = when (sourceFile.extension) {
            "pdf" -> "application/pdf"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            else -> error("Unsupported extension for $sourceFile")
        }
        val content = com.google.api.services.drive.model.File().apply {
            this.name = name
            this.parents = listOf(parent)
        }
        val mediaContent = FileContent(mimeType, sourceFile.toFile())
        return withBackoff { files().update(fileId, content, mediaContent).setFields("id").execute().id }
    }
}
