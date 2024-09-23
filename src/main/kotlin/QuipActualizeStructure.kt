package io.github.jvmusin

import io.github.jvmusin.ProcessAllFiles.Location.Companion.titleWithId
import kenichia.quipapi.QuipFolder
import kenichia.quipapi.QuipThread
import org.apache.http.HttpStatus
import org.apache.http.client.HttpResponseException
import java.nio.file.Path
import kotlin.io.path.*

object QuipActualizeStructure {
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
        path.createDirectories()

        val unnamedProgress = progress.named(id)

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
        logger.info(namedProgress.action("Actualizing folder contents (${folder.children.size} items)"))
        val children = folder.children

        // forget remotely deleted entries
        val childrenIds = children.map { it.id }.toSet()
        path.listDirectoryEntries().forEach { e ->
            if (!e.exists() || e.name == "_folder.json") return@forEach
            if (e.nameWithoutExtension !in childrenIds) {
                // delete
                if (e.isDirectory()) {
                    val name = ProcessAllFiles.FolderLocation(e).titleWithId
                    logger.info(namedProgress.action("Deleting remotely deleted folder $name"))
                    e.deleteRecursively()
                } else {
                    if (!e.isFileJson()) return@forEach
                    val jsonPath = e.resolveSibling(e.nameWithoutExtension + ".json")
                    if (!jsonPath.exists()) {
                        logger.warning(namedProgress.action("Not found json for file ${e.nameWithoutExtension}"))
                        return@forEach
                    }
                    val name = ProcessAllFiles.FileLocation(jsonPath).titleWithId
                    logger.info(namedProgress.action("Deleting remotely deleted file $name"))
                    e.deleteExisting()
                }
            }
        }

        // download new entries
        val existingFileIds = path.listDirectoryEntries().mapNotNull { it.readFileJson() }.map { it.quipThread().id }
        children
            .sortedBy { it.isFolder } // process files before folders
            .forEachIndexed { index, child ->
                if (child.isFolder) {
                    processFolder(child.id, path.resolve(child.id), namedProgress.withIndex(index, children.size))
                } else {
                    if (child.id in existingFileIds) {
                        // skip existing file
                    } else {
                        // download file
                        processFile(child.id, path.resolve(child.id), namedProgress.withIndex(index, children.size))
                    }
                }
            }

        path.resolve("_folder.json").writeJson(folder.toJson())
        logger.info(namedProgress.action("Downloaded folder structure"))
    }
}
