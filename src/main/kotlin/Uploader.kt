package io.github.jvmusin

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.gson.JsonObject
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

fun main() {
    Uploader.saveAllFiles()
}

object Uploader {
    private val logger = getLogger()

    object DriveClientFactory {
        private const val APPLICATION_NAME: String = "Quip to Google Drive Migration"
        private const val TOKENS_DIRECTORY_PATH: String = "tokens"
        private const val CREDENTIALS_FILE_PATH: String = "/credentials.json"
        private val JSON_FACTORY: JsonFactory = GsonFactory.getDefaultInstance()
        private val SCOPES: List<String> = listOf(DriveScopes.DRIVE)

        private fun getCredentials(httpTransport: NetHttpTransport?): Credential {
            // Load client secrets.
            val `in`: InputStream = javaClass.getResourceAsStream(CREDENTIALS_FILE_PATH)
                ?: throw FileNotFoundException("Resource not found: $CREDENTIALS_FILE_PATH")
            val clientSecrets =
                GoogleClientSecrets.load(JSON_FACTORY, InputStreamReader(`in`))

            // Build flow and trigger user authorization request.
            val flow = GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, SCOPES
            )
                .setDataStoreFactory(FileDataStoreFactory(File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build()
            val receiver = LocalServerReceiver.Builder().setPort(8888).build()
            val credential = AuthorizationCodeInstalledApp(flow, receiver).authorize("user")
            //returns an authorized Credential object.
            return credential
        }

        fun createService(): Drive {
            val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
            val service = Drive.Builder(httpTransport, JSON_FACTORY, getCredentials(httpTransport))
                .setApplicationName(APPLICATION_NAME)
                .build()
            return service
        }
    }

    private fun getOrCreateFolder(service: Drive, name: String, parent: String?): String {
        val folderMimeType = "application/vnd.google-apps.folder"
        val acceptableFolders = service.files().list().execute().files.filter {
            it.name == name && it.mimeType == folderMimeType
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
        return service.files().create(content).setFields("id").execute().id
    }

    private fun createFile(service: Drive, parent: String, name: String, sourceFile: Path): String {
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
        return service.files().create(content, mediaContent).setFields("id").execute().id
    }

    data class DriveFile(val id: String, val name: String)

    private fun getFilesInFolder(service: Drive, folderId: String): List<DriveFile> {
        return service.files().list()
            .setQ("parents in '$folderId' and mimeType!='application/vnd.google-apps.folder'")
            .execute()
            .files
            .map { DriveFile(it.id, it.name) }
    }

    data class DriveFileInfo(val driveId: String, val existed: Boolean) {
        companion object {
            fun newFile(id: String) = DriveFileInfo(id, false)
            fun existedFile(id: String) = DriveFileInfo(id, true)
        }
    }

    @OptIn(ExperimentalPathApi::class)
    fun saveAllFiles() {
        val service = DriveClientFactory.createService()

        val quipFolder = getOrCreateFolder(service, name = "Quip", parent = null)

        Paths.get("downloaded").visitFileTree {
            val driveFolderIds = mutableListOf(quipFolder)
            val driveFolderNames = mutableListOf<String>()
            val driveFolderFiles = mutableListOf<List<DriveFile>>()

            fun JsonObject.getTitle() = getAsJsonPrimitive("title").asString
            fun JsonObject.getId() = getAsJsonPrimitive("id").asString
            fun JsonObject.getTitleAndId() = "${getTitle()} (${getId()})"

            onVisitFile { file, attributes ->
                if (file.extension == "json") return@onVisitFile FileVisitResult.CONTINUE
                if (file.name.endsWith("_driveId.txt")) {
                    Files.delete(file)
                    return@onVisitFile FileVisitResult.CONTINUE
                }

                val json = file.resolveSibling(file.nameWithoutExtension + ".json").readText()
                val parsedJson = gson().fromJson(
                    json, JsonObject::class.java
                ).getAsJsonObject("thread")
                val title = parsedJson.getTitle()
                val fullFileName = (driveFolderNames + parsedJson.getTitleAndId()).joinToString(" > ")

                val driveInfoPath = file.resolveSibling(file.nameWithoutExtension + "_driveInfo.json")
                if (driveInfoPath.exists()) {
                    val info = gson().fromJson(driveInfoPath.readText(), DriveFileInfo::class.java)
                    // the file is already on drive
                    logger.info("$fullFileName -- Skipping previously uploaded file with Drive id ${info.driveId}")
                    return@onVisitFile FileVisitResult.CONTINUE
                }

                val driveFile = driveFolderFiles.last().firstOrNull { it.name == parsedJson.getTitle() }
                if (driveFile != null) {
                    logger.info("$fullFileName -- Skipping file, same-named file is already on drive with id ${driveFile.id}")
                    driveInfoPath.createNewFile(DriveFileInfo.existedFile(driveFile.id))
                    return@onVisitFile FileVisitResult.CONTINUE
                }
                logger.info("$fullFileName -- Saving file on Drive")
                val driveId = createFile(service, parent = driveFolderIds.last(), name = title, sourceFile = file)
                driveInfoPath.createNewFile(DriveFileInfo.newFile(driveId))
                logger.info("$fullFileName -- Saved file on Drive with id $driveId")
                FileVisitResult.CONTINUE
            }

            onPreVisitDirectory { directory, attributes ->
                val folderJson = directory.resolve("_folder.json").readText()
                val folderJsonParsed = gson().fromJson(folderJson, JsonObject::class.java)
                val title = folderJsonParsed.getTitle()
                val driveFolder = getOrCreateFolder(service, title, driveFolderIds.last())

                driveFolderIds += driveFolder
                driveFolderNames += folderJsonParsed.getTitleAndId()
                driveFolderFiles += getFilesInFolder(service, driveFolder)

                FileVisitResult.CONTINUE
            }

            onPostVisitDirectory { directory, exception ->
                driveFolderIds.removeLast()
                driveFolderNames.removeLast()
                driveFolderFiles.removeLast()
                FileVisitResult.CONTINUE
            }
        }
    }
}
