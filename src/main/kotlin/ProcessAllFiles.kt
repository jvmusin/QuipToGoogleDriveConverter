package io.github.jvmusin

import io.github.jvmusin.ProcessAllFiles.Location.Companion.titleWithId
import java.nio.file.Path
import kotlin.io.path.*

abstract class ProcessAllFiles(private val processName: String? = null) {
    private val progresses = ArrayDeque<Progress>()
    private fun progress() = progresses.last()
    private val logger = getLogger()

    private fun processFolder(location: FolderLocation) {
        progresses.addLast(progress().named(location.titleWithId))
        beforeVisitFolder(location)
        val entries = location.path.listDirectoryEntries()
            .sortedBy { it.name } // for consistency
            .sortedBy { !it.isRegularFile() } // files then folders
        for (p in entries) {
            if (p.isDirectory()) processFolder(FolderLocation(p)) else if (p.isFileJson()) {
                val fileLocation = FileLocation(p)
                progresses.addLast(progress().named(fileLocation.titleWithId))
                visitFile(fileLocation)
                progresses.removeLast()
            }
        }
        afterVisitFolder(location)
        progresses.removeLast()
    }

    fun run() {
        progresses.clear()
        progresses += Progress("")

        processName?.let { log("Started $processName") }
        processFolder(FolderLocation(downloadedPath))
        processName?.let { log("Finished $processName") }
    }

    open fun beforeVisitFolder(location: FolderLocation) {}
    open fun afterVisitFolder(location: FolderLocation) {}
    open fun visitFile(location: FileLocation) {}

    fun log(message: String) {
        logger.info(progress().action(message))
    }

    interface Location {
        val path: Path
        val id: String
        val title: String

        companion object {
            val Location.titleWithId: String get() = "$title ($id)"
        }
    }

    class FileLocation(
        override val path: Path,
    ) : Location {
        override val id = path.nameWithoutExtension
        val json = path.readFileJson()!!
        override val title = json.quipThread().title
        val type = QuipFileType.fromQuipThread(json.quipThread())
        val documentPath = path.resolveSibling("$id.${type.extension}")
        val commentsPath: Path = path.resolveSibling("${id}_comments.md")
        val updatedDocumentPath: Path =
            documentPath.resolveSibling(documentPath.name.replace(".", "_updated."))
    }

    class FolderLocation(override val path: Path) : Location {
        val json = path.resolve("_folder.json").readFolderJson()!!
        override val id = json.quipFolder().id
        override val title = json.quipFolder().title
    }
}
