package io.github.jvmusin

import io.github.jvmusin.ProcessAllFiles.Location.Companion.titleWithId
import java.nio.file.FileVisitResult
import java.nio.file.Path
import kotlin.io.path.*

abstract class ProcessAllFiles(private val processName: String? = null) {
    private val progresses = ArrayDeque<Progress>()
    private var currentFileIndex = 0
    private var totalFilesCount = 0
    private fun progress() = progresses.last()
    private val logger = getLogger()

    private fun processFolder(location: FolderLocation, index: Int, total: Int) {
        progresses.addLast(progress().named(location.titleWithId).withIndex(index, total))
        beforeVisitFolder(location)
        val entries = location.path.listDirectoryEntries()
            .mapNotNull {
                when {
                    it.isFileJson() -> FileLocation(it)
                    it.isDirectory() -> FolderLocation(it)
                    else -> null
                }
            }
            .sortedBy { it.title } // for better order on Google Drive
            .sortedBy { it is FolderLocation } // files then folders
        for ((i, p) in entries.withIndex()) {
            when (p) {
                is FolderLocation -> processFolder(p, i, entries.size)
                is FileLocation -> {
                    progresses.addLast(
                        progress().named(p.titleWithId).withIndex(i, entries.size)
                            .withPrefixIndex(currentFileIndex++, totalFilesCount)
                    )
                    visitFile(p)
                    progresses.removeLast()
                }

                else -> error("not possible: not file and not folder")
            }
        }
        afterVisitFolder(location)
        progresses.removeLast()
    }

    private fun countAllFileJsons(): Int {
        var count = 0
        downloadedPath.visitFileTree {
            onVisitFile { file, attributes ->
                if (file.isFileJson()) {
                    count++
                }
                FileVisitResult.CONTINUE
            }
        }
        return count
    }

    fun run() {
        progresses.clear()
        progresses += Progress("")
        currentFileIndex = 0
        totalFilesCount = countAllFileJsons()

        processName?.let { log("Started $processName") }
        processFolder(FolderLocation(downloadedPath), 0, 1)
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
        override val id: String = path.nameWithoutExtension
        val json: FileJson = path.readFileJson()!!
        override val title: String = json.quipThread().title
        val type: QuipFileType = QuipFileType.fromQuipThread(json.quipThread())
        val documentPath: Path = path.resolveSibling("$id.${type.extension}")

        val withCommentsDocumentPath: Path =
            documentPath.resolveSibling(
                documentPath.name.replace(
                    oldValue = ".",
                    newValue = "_with_comments."
                )
            )
        val withCommentsAndAuthorDocumentPath: Path =
            withCommentsDocumentPath.resolveSibling(
                documentPath.name.replace(
                    oldValue = ".",
                    newValue = "_with_comments_and_author."
                )
            )
        val withCommentsAndAuthorAndLinksDocumentPath: Path =
            withCommentsDocumentPath.resolveSibling(
                documentPath.name.replace(
                    oldValue = ".",
                    newValue = "_with_comments_and_author_and_links."
                )
            )

        fun updateJson(block: FileJson.() -> Unit) {
            val newJson = json.copy().also(block)
            path.writeJson(newJson)
        }
    }

    class FolderLocation(override val path: Path) : Location {
        val json = path.resolve("_folder.json").readFolderJson()!!
        override val id = json.quipFolder().id
        override val title = json.quipFolder().title

        fun updateJson(block: FolderJson.() -> Unit) {
            val newJson = json.copy().also(block)
            path.writeJson(newJson)
        }
    }
}
