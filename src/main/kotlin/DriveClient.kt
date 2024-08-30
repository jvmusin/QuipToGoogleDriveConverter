package io.github.jvmusin

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import java.nio.file.Path
import kotlin.io.path.extension

class DriveClient(private val service: Drive) {
    data class DriveResource(val id: String, val name: String, val webViewLink: String, val mimeType: String)

    enum class IdType {
        FILES,
        SHORTCUTS
    }

    fun generateIds(count: Int, type: IdType): List<String> {
        require(count >= 0)

        val ids = mutableListOf<String>()
        while (ids.size < count) {
            val generateNow = minOf(1000, count - ids.size)
            val generatedIds = withBackoff {
                service.files().generateIds()
                    .setCount(generateNow)
                    .setType(type.name.lowercase())
                    .execute()
                    .ids
            }
            require(generateNow == generatedIds.size)
            ids += generatedIds
        }

        return ids
    }

    fun listFolderContents(folderId: String?): List<DriveResource> {
        val folderNames = mutableListOf<DriveResource>()
        var pageToken: String? = null

        do {
            val result = withBackoff {
                service.files().list()
                    .setQ("'${folderId ?: "root"}' in parents")
                    .setSpaces("drive")
                    .setFields("nextPageToken, files(id, name, webViewLink, mimeType)")
                    .setPageToken(pageToken)
                    .execute()
            }

            folderNames += result.files.map { DriveResource(it.id, it.name, it.webViewLink, it.mimeType) }

            pageToken = result.nextPageToken
        } while (pageToken != null)

        return folderNames
    }

    fun createFolder(name: String, id: String, parent: String?): String {
        val content = com.google.api.services.drive.model.File().apply {
            this.name = name
            this.mimeType = FOLDER_MIME_TYPE
            this.parents = listOfNotNull(parent)
            this.id = id
        }
        return withBackoff {
            try {
                service.files().create(content).setFields("id").execute().id
            } catch (e: GoogleJsonResponseException) {
                if (e.details.message == "A file already exists with the provided ID.") {
                    return@withBackoff id
                }
                throw e
            }
        }
    }

    private fun Path.mimeType() = when (extension) {
        "pdf" -> "application/pdf"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "md" -> "text/markdown"
        else -> error("Unsupported extension for $this")
    }

    fun createFile(name: String, id: String, parent: String, sourceFile: Path): String {
        val mimeType = sourceFile.mimeType()
        val content = com.google.api.services.drive.model.File().apply {
            this.name = name
            this.parents = listOf(parent)
            this.id = id
        }
        val mediaContent = FileContent(mimeType, sourceFile.toFile())
        return withBackoff {
            try {
                service.files().create(content, mediaContent).setFields("id").execute().id
            } catch (e: GoogleJsonResponseException) {
                if (e.details.message == "A file already exists with the provided ID.") {
                    return@withBackoff id
                }
                throw e
            }
        }
    }

    fun createShortcut(targetId: String, parentFolderId: String, shortcutName: String, id: String): File {
        val shortcutMetadata = File().apply {
            this.name = shortcutName
            this.mimeType = SHORTCUT_MIME_TYPE
            this.shortcutDetails = File.ShortcutDetails().setTargetId(targetId)
            this.parents = listOfNotNull(parentFolderId)
            this.id = id
        }

        val shortcut: File = service.files().create(shortcutMetadata)
            .setFields("id, name, webViewLink, shortcutDetails")
            .execute()

        return shortcut
    }

    private companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val SHORTCUT_MIME_TYPE = "application/vnd.google-apps.shortcut"
    }
}
