package io.github.jvmusin

import com.google.gson.JsonObject
import java.nio.file.FileVisitResult
import java.nio.file.Path
import kotlin.io.path.*

object DriveUploadFiles {
    @JvmStatic
    fun main(args: Array<String>) {
        val client = DriveClientFactory.createClient()
        val quipFolder = client.getOrCreateFolder(name = Settings.read().driveFolderName, parent = null)
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
        require(file.extension == "json" || file.name != "_folder.json") {
            "Not a json file for a document: $file"
        }

        val fileJson = gson().fromJson(
            file.readText(), FileJson::class.java
        )
        val parsedJson = fileJson.quip.getAsJsonObject("thread")
        val fullFileName = (state.driveFolderNames + parsedJson.getTitleAndId()).joinToString(" > ")

        if (fileJson.driveInfo != null) {
            logger.info("$fullFileName -- Skipping previously uploaded file with Drive id ${fileJson.driveInfo.id}")
            return
        }

        logger.info("$fullFileName -- Saving file on Drive")
        val document = file.resolveSibling(fileJson.fileName)
        val driveId =
            service.createFile(parent = state.driveFolderId, name = parsedJson.getTitle(), sourceFile = document)
        file.deleteExisting()
        file.createNewFile(fileJson.copy(driveInfo = FileDriveInfo(driveId)))
        logger.info("$fullFileName -- Saved file on Drive with id $driveId")
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
