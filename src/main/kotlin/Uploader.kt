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
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

fun main() {
    Uploader.saveAllFiles()
}

object Uploader {
    private val logger = getLogger()
    private const val APPLICATION_NAME: String = "Quip to Google Drive Migration"
    private val JSON_FACTORY: JsonFactory = GsonFactory.getDefaultInstance()
    private const val TOKENS_DIRECTORY_PATH: String = "tokens"
    private val SCOPES: List<String> = listOf(DriveScopes.DRIVE)
    private const val CREDENTIALS_FILE_PATH: String = "/credentials.json"

    private fun getCredentials(httpTransport: NetHttpTransport?): Credential {
        // Load client secrets.
        val `in`: InputStream = Uploader::class.java.getResourceAsStream(CREDENTIALS_FILE_PATH)
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

    private fun getOrCreateFolder(service: Drive, name: String, parent: String?): String {
        val acceptableFolders = service.files().list().execute().files.filter { it.name == name }
        require(acceptableFolders.size < 2) {
            "Multiple folders named '$name' found"
        }
        if (acceptableFolders.size == 1) return acceptableFolders[0].id
        val content = com.google.api.services.drive.model.File().apply {
            this.name = name
            this.mimeType = "application/vnd.google-apps.folder"
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
        // Build a new authorized API client service.
        val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
        val service = Drive.Builder(httpTransport, JSON_FACTORY, getCredentials(httpTransport))
            .setApplicationName(APPLICATION_NAME)
            .build()

        val quipFolder = getOrCreateFolder(service, name = "Quip", parent = null)

        Paths.get("downloaded").visitFileTree {
            val driveFolderIds = mutableListOf(quipFolder)
            val driveFolderNames = mutableListOf<String>()
            val driveFolderFiles = mutableListOf<List<DriveFile>>()

            onVisitFile { file, attributes ->
                if (file.extension == "json") return@onVisitFile FileVisitResult.CONTINUE
                val driveInfoPath = file.resolveSibling(file.nameWithoutExtension + "_driveId.txt")
                if (driveInfoPath.exists()) {
                    // the file is already on drive
                    return@onVisitFile FileVisitResult.CONTINUE
                }
                val json = file.resolveSibling(file.nameWithoutExtension + ".json").readText()
                val title = gson().fromJson(
                    json, JsonObject::class.java
                ).getAsJsonObject("thread").get("title").asString
                val fullFileName = (driveFolderNames + title).joinToString("/")
                val driveFile = driveFolderFiles.last().firstOrNull { it.name == title }
                if (driveFile != null) {
                    logger.info("Skipping existing file $fullFileName")
                    driveInfoPath.createNewFile(DriveFileInfo.existedFile(driveFile.id))
                    return@onVisitFile FileVisitResult.CONTINUE
                }
                logger.info("Saving file $fullFileName")
                val driveId = createFile(service, parent = driveFolderIds.last(), name = title, sourceFile = file)
                driveInfoPath.createNewFile(DriveFileInfo.newFile(driveId))
                logger.info("Saved file $fullFileName")
                FileVisitResult.CONTINUE
            }

            onPreVisitDirectory { directory, attributes ->
                val title = try {
                    directory.resolve("_title.txt").readText()
                } catch (e: Exception) {
                    // might fire if I have no access to a folder on quip
                    directory.name // hack until all files from quip are downloaded, DO NOT DO THIS ON PROD, JUST SKIP SUCH FOLDERS
                }
                driveFolderIds += getOrCreateFolder(service, title, driveFolderIds.last())
                driveFolderNames += title
                driveFolderFiles += getFilesInFolder(service, driveFolderIds.last())
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
