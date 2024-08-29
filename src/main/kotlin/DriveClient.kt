package io.github.jvmusin

import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import java.nio.file.Path
import kotlin.io.path.extension

class DriveClient(private val service: Drive) {
    data class DriveResource(val id: String, val name: String, val webViewLink: String)

    fun listFolders(parentFolderId: String?): List<DriveResource> {
        val folderNames = mutableListOf<DriveResource>()
        var pageToken: String? = null

        do {
            val result = withBackoff {
                service.files().list()
                    .setQ("'${parentFolderId ?: "root"}' in parents and mimeType = '$FOLDER_MIME_TYPE'")
                    .setSpaces("drive")
                    .setFields("nextPageToken, files(id, name, webViewLink)")
                    .setPageToken(pageToken)
                    .execute()
            }

            folderNames += result.files.map { DriveResource(it.id, it.name, it.webViewLink) }

            pageToken = result.nextPageToken
        } while (pageToken != null)

        return folderNames
    }

    fun listFolderContents(folderId: String?): List<DriveResource> {
        val folderNames = mutableListOf<DriveResource>()
        var pageToken: String? = null

        do {
            val result = withBackoff {
                service.files().list()
                    .setQ("'${folderId ?: "root"}' in parents")
                    .setSpaces("drive")
                    .setFields("nextPageToken, files(id, name, webViewLink)")
                    .setPageToken(pageToken)
                    .execute()
            }

            folderNames += result.files.map { DriveResource(it.id, it.name, it.webViewLink) }

            pageToken = result.nextPageToken
        } while (pageToken != null)

        return folderNames
    }

    fun getFolder(name: String, parent: String?): String? {
        val acceptableFolders = listFolders(parent).filter { it.name == name }
        require(acceptableFolders.size < 2) {
            "Multiple folders named '$name' found"
        }
        return acceptableFolders.singleOrNull()?.id
    }

    fun createFolderOrThrowIfExists(name: String, parent: String?): String {
        require(getFolder(name, parent) == null) {
            "Folder named `$name` already exists"
        }
        return createFolder(name, parent)
    }

    fun createFolder(name: String, parent: String?): String {
        val content = com.google.api.services.drive.model.File().apply {
            this.name = name
            this.mimeType = FOLDER_MIME_TYPE
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
            }.execute().id
        }
    }

    fun updateFile(fileId: String, sourceFile: Path) {
        val mimeType = sourceFile.mimeType()
        val content = com.google.api.services.drive.model.File()
        val mediaContent = FileContent(mimeType, sourceFile.toFile())
        withBackoff { service.files().update(fileId, content, mediaContent).execute() }
    }

    private companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    }
}
