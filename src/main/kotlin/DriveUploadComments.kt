package io.github.jvmusin

import com.google.gson.JsonObject
import java.nio.file.FileVisitResult
import java.nio.file.Path
import kotlin.io.path.*

object DriveUploadComments {
    @JvmStatic
    fun main(args: Array<String>) {
        val client = DriveClientFactory.createClient()
        val settings = Settings.read()
        val quipFolder = client.getOrCreateFolder(name = settings.driveFolderName, parent = null)
        visitDirectory(
            downloadedPath,
            State(
                driveFolderId = quipFolder,
                driveFolderNames = emptyList(),
            ),
            client
        )
    }

    private val logger = getLogger()

    private fun JsonObject.getTitle() = getAsJsonPrimitive("title").asString
    private fun JsonObject.getId() = getAsJsonPrimitive("id").asString
    private fun JsonObject.getTitleAndId() = "${getTitle()} (${getId()})"

    data class State(
        val driveFolderId: String,
        val driveFolderNames: List<String>,
    )

    private fun visitDocumentJsonFile(file: Path, state: State, service: DriveClient) {
        val fileJson = requireNotNull(file.readFileJson()) {
            "Not a json file for a document: $file"
        }
        val parsedJson = fileJson.quip.getAsJsonObject("thread")
        val fullFileName = (state.driveFolderNames + parsedJson.getTitleAndId()).joinToString(" > ")

        val commentsFile = file.resolveSibling("${file.nameWithoutExtension}_comments.md")
        if (!commentsFile.exists()) {
            logger.info("$fullFileName -- Comments file not found, skipping")
            return
        }

        val driveInfo = requireNotNull(fileJson.driveInfo) {
            "$fullFileName -- File is not uploaded to Google Drive"
        }
        if (driveInfo.commentsId != null && !Settings.read().forceDriveReupload) {
            logger.info("$fullFileName -- Skipping previously uploaded comments file with Drive id ${driveInfo.commentsId}")
            return
        }

        logger.info("$fullFileName -- Saving comments file on Drive")
        val driveId = service.createFile(
            parent = state.driveFolderId,
            name = parsedJson.getTitle() + " [comments]",
            sourceFile = commentsFile
        )
        file.deleteExisting()
        file.createNewFile(fileJson.copy(driveInfo = driveInfo.copy(commentsId = driveId)))
        logger.info("$fullFileName -- Saved comments file on Drive with id $driveId")
        FileVisitResult.CONTINUE
    }

    private fun visitDirectory(directory: Path, state: State, service: DriveClient) {
        val folderJson = directory.resolve("_folder.json").readText()
        val folderJsonParsed = gson().fromJson(folderJson, FolderJson::class.java)
        val folderQuipParsed = folderJsonParsed.quip.getAsJsonObject("folder")
        val title = folderQuipParsed.getTitle()
        val driveFolderId = service.getOrCreateFolder(title, state.driveFolderId)

        val eligibleFiles = directory.listDirectoryEntries().filter {
            it.isDirectory() || (it.extension == "json" && it.name != "_folder.json")
        }
        for ((i, child) in eligibleFiles.sortedByDescending { it.isDirectory() }.withIndex()) {
            val titleForLogs = folderQuipParsed.getTitleAndId() + " (${i + 1}/${eligibleFiles.size})"
            val newState = State(
                driveFolderId,
                state.driveFolderNames + titleForLogs,
            )
            if (child.isDirectory()) visitDirectory(child, newState, service)
            else visitDocumentJsonFile(child, newState, service)
        }
    }
}
