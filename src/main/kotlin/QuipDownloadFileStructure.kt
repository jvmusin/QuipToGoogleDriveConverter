package io.github.jvmusin

import kenichia.quipapi.QuipFolder
import kenichia.quipapi.QuipThread
import org.apache.http.HttpStatus
import org.apache.http.client.HttpResponseException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists

object QuipDownloadFileStructure {
    @JvmStatic
    fun main(args: Array<String>) {
        setupQuipClient()
        val quipFolderId = Settings.read().quipFolderId
        processFolder(quipFolderId, downloadedPath, Progress(""))
    }

    private val logger = getLogger()

    private fun processFile(id: String, path: Path, progress: Progress) {
        val folderPath = path.parent

        val unnamedProgress = progress.named(id)
        val jsonPath = folderPath.resolve("$id.json")
        if (jsonPath.exists()) {
            logger.info(unnamedProgress.action("Skipping previously downloaded file info"))
            return
        }

        logger.info(unnamedProgress.action("Downloading file info"))
        val file = withBackoff { QuipThread.getThread(id) }
        jsonPath.writeJson(file.toJson())
        logger.info(progress.named("${file.title} (${file.id})").action("Downloaded file info"))
    }

    private fun processFolder(id: String, path: Path, progress: Progress) {
        if (!path.exists()) path.createDirectories()

        val unnamedProgress = progress.named(id)
        val jsonPath = path.resolve("_folder.json")
        if (jsonPath.exists()) {
            logger.info(unnamedProgress.action("Skipping previously downloaded folder"))
            return
        }

        val folder = try {
            logger.info(unnamedProgress.action("Downloading folder info"))
            QuipFolder.getFolder(id, false).also {
                logger.info(unnamedProgress.action("Downloaded folder info"))
            }
        } catch (e: HttpResponseException) {
            if (e.statusCode == HttpStatus.SC_FORBIDDEN) {
                logger.warning(unnamedProgress.action("No access to the folder, skipping"))
                path.deleteExisting()
                return
            }
            throw e
        }

        val namedProgress = progress.named(folder.title + " (${folder.id})")
        logger.info(namedProgress.action("Downloading folder contents (${folder.children.size} items)"))
        val children = folder.children
        children
            .sortedBy { it.isFolder } // process files before folders
            .forEachIndexed { index, child ->
                process(child, path.resolve(child.id), namedProgress.withIndex(index + 1, children.size))
            }
        jsonPath.writeJson(folder.toJson())
        logger.info(namedProgress.action("Downloaded folder structure"))
    }

    private fun process(node: QuipFolder.Node, path: Path, progress: Progress) {
        if (node.isFolder) {
            processFolder(node.id, path, progress)
        } else {
            processFile(node.id, path, progress)
        }
    }
}
