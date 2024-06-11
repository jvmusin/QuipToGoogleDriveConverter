package io.github.jvmusin

import kenichia.quipapi.QuipFolder
import kenichia.quipapi.QuipThread
import org.apache.http.client.HttpResponseException
import java.nio.file.Path
import kotlin.io.path.*

object QuipDownloader {
    @JvmStatic
    fun main(args: Array<String>) {
        setupQuipClient()
        processFolder(downloadedPath, KOTLIN_FOLDER_ID, Progress(""))
    }

    private val logger = getLogger()
    private const val KOTLIN_FOLDER_ID = "FXfSOQ5OETJh"

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

    data class Progress(private val s: String) {
        fun named(name: String) = Progress("$s > $name")
        fun withIndex(index: Int, total: Int) = Progress("$s ($index/$total)")
        fun action(name: String) = "$s -- $name"
        override fun toString() = s
    }

    private fun QuipFolder.Node.processThread(path: Path, progress: Progress) {
        path.createParentDirectories()
        val folderPath = path.parent

        val unnamedProgress = progress.named(id)
        val jsonPath = folderPath.resolve("$id.json")
        if (jsonPath.exists()) {
            logger.info(unnamedProgress.action("Skipping thread"))
            return
        }

        val thread = QuipThread.getThread(id)
        val namedProgress = progress.named("${thread.title} (${thread.id})")
        logger.info(namedProgress.action("Downloading thread"))
        val type = QuipThreadType.fromQuipThread(thread)
        val fileName = thread.id + "." + type.extension
        folderPath.resolve(fileName).createNewFile(type.download(thread))
        jsonPath.createNewFile(thread.toJson(fileName))
        logger.info(namedProgress.action("Downloaded thread"))
    }

    @OptIn(ExperimentalPathApi::class)
    private fun processFolder(path: Path, id: String, progress: Progress) {
        if (!path.exists()) {
            path.createParentDirectories()
            path.createDirectory()
        }

        val unnamedProgress = progress.named(id)
        val jsonPath = path.resolve("_folder.json")
        if (jsonPath.exists()) {
            logger.info(unnamedProgress.action("Skipping folder"))
            return
        }

        val folder = try {
            QuipFolder.getFolder(id, false)
        } catch (e: HttpResponseException) {
            if (e.statusCode == 403) {
                logger.warning(unnamedProgress.action("No access, skipping"))
                path.deleteRecursively()
                return
            }
            throw e
        }

        val namedProgress = progress.named(folder.title + " (${folder.id})")
        logger.info(namedProgress.action("Downloading folder"))
        val children = folder.children
        children
            .sortedBy { it.isFolder } // process files before folders
            .forEachIndexed { index, child ->
                child.goDeep(path.resolve(child.id), namedProgress.withIndex(index + 1, children.size))
            }
        jsonPath.createNewFile(folder.toJson())
        logger.info(namedProgress.action("Downloaded folder"))
    }

    private fun QuipFolder.Node.goDeep(path: Path, progress: Progress) {
        if (isFolder) {
            processFolder(path, id, progress)
        } else {
            processThread(path, progress)
        }
    }
}
