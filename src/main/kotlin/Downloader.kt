package io.github.jvmusin

import kenichia.quipapi.QuipClient
import kenichia.quipapi.QuipFolder
import kenichia.quipapi.QuipThread
import org.apache.http.client.HttpResponseException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

fun main() {
    Downloader.run()
}

object Downloader {
    private val logger = getLogger()

    fun run() {
        QuipClient.enableDebug(true)
        QuipClient.setAccessToken(javaClass.getResource("/quip_access_token.txt")!!.readText())
        val kotlinFolderId = "FXfSOQ5OETJh"
        val downloadedPath = Paths.get("downloaded")
        processFolder(downloadedPath, kotlinFolderId, emptyList())
    }

    private enum class QuipThreadType(
        val quipType: QuipThread.Type,
        val extension: String,
        val download: QuipThread.() -> ByteArray
    ) {
        Docx(QuipThread.Type.DOCUMENT, "docx", QuipThread::exportAsDocx),
        Slides(QuipThread.Type.SLIDES, "pdf", QuipThread::exportAsPdf),
        Spreadsheet(QuipThread.Type.SPREADSHEET, "xlsx", QuipThread::exportAsXlsx);

        companion object {
            fun fromQuipThread(thread: QuipThread): QuipThreadType {
                return entries.first { it.quipType == thread.type }
            }
        }
    }

    private fun QuipFolder.Node.processThread(path: Path, parentTitles: List<String>) {
        val folderPath = path.parent
        if (!folderPath.exists()) {
            path.createParentDirectories()
        }

        val downloadedIds = Files.list(folderPath).map { it.nameWithoutExtension }.toList()
        if (id in downloadedIds) {
            logger.info("Skipping thread ${parentTitles.plus(id).joinToString("/")}")
            return
        }

        val thread = QuipThread.getThread(id)
        val newTitles = parentTitles.plus("${thread.id}:${thread.title}")
        val fullThreadName = newTitles.joinToString("/")
        logger.info("Downloading thread $fullThreadName")
        val type = QuipThreadType.fromQuipThread(thread)
        val docxPath = folderPath.resolve(path.name + "." + type.extension)
        docxPath.writeBytes(type.download(thread))

        val titlePath = folderPath.resolve(path.name + "_title.txt")
        titlePath.writeText(
            thread.title,
            Charsets.UTF_8,
            StandardOpenOption.CREATE_NEW
        )
        logger.info("Finished downloading thread $fullThreadName")
    }

    private fun processFolder(path: Path, id: String, parentTitles: List<String>) {
        if (!path.exists()) {
            path.createParentDirectories()
            path.createDirectory()
        }

        val titlePath = path.resolve("_title.txt")
        if (titlePath.exists()) {
            logger.info("Skipping folder ${parentTitles.plus(id).joinToString("/")}")
            return
        }

        val folder = try {
            QuipFolder.getFolder(id, false)
        } catch (e: Exception) {
            if (e is HttpResponseException && e.statusCode == 403) {
                logger.warning("No access to the folder $id, path $parentTitles")
                val noAccessFile = path.resolve("_no_access.txt")
                if (!noAccessFile.exists()) noAccessFile.createFile()
            }
            return
        }
        val newTitles = parentTitles.plus("${folder.id}:${folder.title}")
        val fullFolderName = newTitles.joinToString("/")
        logger.info("Downloading folder $fullFolderName")
        for (child in folder.children) {
            child.goDeep(path.resolve(child.id), newTitles)
        }
        titlePath.writeText(
            folder.title,
            Charsets.UTF_8,
            StandardOpenOption.CREATE_NEW
        )
        logger.info("Finished downloading folder $fullFolderName")
    }

    private fun QuipFolder.Node.goDeep(path: Path, parentTitles: List<String>) {
        if (isFolder) {
            processFolder(path, id, parentTitles)
        } else {
            processThread(path, parentTitles)
        }
    }
}
