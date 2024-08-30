package io.github.jvmusin

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import java.nio.file.Path
import kotlin.io.path.extension

class DriveClient(private val service: Drive) {
    data class DriveResource(val id: String, val name: String, val webViewLink: String, val mimeType: String)

    fun generateIds(count: Int): List<String> {
        require(count >= 0)
        if (count == 0) return emptyList()

        val ids = mutableListOf<String>()
        while (ids.size < count) {
            val generateNow = minOf(1000, count - ids.size)
            val generatedIds = withBackoff {
                service.files().generateIds()
                    .setCount(generateNow)
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

    private companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    }
}
