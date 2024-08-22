package io.github.jvmusin

import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import java.nio.file.Path
import kotlin.io.path.extension

class DriveClient(private val service: Drive) {
    fun getOrCreateFolder(name: String, parent: String?): String {
        val folderMimeType = "application/vnd.google-apps.folder"
        val acceptableFolders = withBackoff {
            service.files().list().execute().files.filter {
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
        return withBackoff { service.files().create(content).setFields("id").execute().id }
    }

    private fun Path.mimeType() = when (extension) {
        "pdf" -> "application/pdf"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "md" -> "text/markdown"
        else -> error("Unsupported extension for $this")
    }

    fun createFile(parent: String, name: String, sourceFile: Path): String {
        val mimeType = sourceFile.mimeType()
        val content = com.google.api.services.drive.model.File().apply {
            this.name = name
            this.parents = listOf(parent)
        }
        val mediaContent = FileContent(mimeType, sourceFile.toFile())
        return withBackoff {
            service.files().create(content, mediaContent).apply {
                fields = "id"
                isSupportsAllDrives = true
            }.execute().id
        }
    }

    fun updateFile(fileId: String, sourceFile: Path) {
        val mimeType = sourceFile.mimeType()
        val content = com.google.api.services.drive.model.File()
        val mediaContent = FileContent(mimeType, sourceFile.toFile())
        withBackoff { service.files().update(fileId, content, mediaContent).setSupportsAllDrives(true).execute() }
    }
}
