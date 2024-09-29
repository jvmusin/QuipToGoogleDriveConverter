package io.github.jvmusin

import java.nio.file.Paths
import kotlin.io.path.writeLines

object CollectMigratedFilesTsv {
    data class Info(
        val title: String,
        val author: String,
        val quipId: String,
        val quipLink: String,
        val driveId: String,
        val driveLink: String,
        val originalFileDriveLink: String?,
        val type: String,
        val path: List<String>
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val userRepository = QuipUserRepository.INSTANCE
        fun getUser(id: String): String {
            val name = userRepository.getUserName(id).orEmpty()
            val email = userRepository.getUserEmail(id)
            return when {
                email == null -> name
                else -> "$name ($email)"
            }
        }

        val path = mutableListOf<String>()
        val infos = mutableListOf<Info>()
        object : ProcessAllFiles() {
            override fun beforeVisitFolder(location: FolderLocation) {
                // save folder
                val quipFolder = location.json.quipFolder()
                infos += Info(
                    title = quipFolder.title,
                    author = getUser(quipFolder.creatorId),
                    quipId = quipFolder.id,
                    quipLink = quipFolder.link,
                    driveId = location.json.driveFolderId!!,
                    driveLink = "https://drive.google.com/drive/u/0/folders/${location.json.driveFolderId!!}",
                    originalFileDriveLink = null,
                    type = "folder",
                    path = path.toList()
                )
                path += location.title
            }

            override fun afterVisitFolder(location: FolderLocation) {
                path.removeLast()
            }

            override fun visitFile(location: FileLocation) {
                val driveFileId = location.json.driveFileId!!
                val isShortcut = location.json.originalDriveFileId != null
                val extension = location.type.extension
                val quipThread = location.json.quipThread()
                val type = quipThread.type
                infos += Info(
                    title = quipThread.title,
                    author = getUser(quipThread.authorId),
                    quipId = quipThread.id,
                    quipLink = quipThread.link,
                    driveId = driveFileId,
                    driveLink = buildDriveFileLink(driveFileId, type),
                    originalFileDriveLink = location.json.originalDriveFileId?.let {
                        buildDriveFileLink(it, type)
                    },
                    type = if (isShortcut) "Shortcut for $extension" else extension,
                    path = path.toList()
                )
            }
        }.run()

        data class Field(val name: String, val value: Info.() -> String)

        val fields = listOf(
            Field("Author") { author },
            Field("Title") { title },
            Field("Type") { type },
            Field("Drive Link") { driveLink },
            Field("Path") { this.path.joinToString(" > ") },
            Field("Quip ID") { quipId },
            Field("Quip Link") { quipLink },
            Field("Original File Drive Link") { originalFileDriveLink.orEmpty() }
        )

        val lines = infos.map { info ->
            fields.joinToString("\t") { field -> field.value(info) }.also {
                require(it.split('\t').size == fields.size)
            }
        }
        val resultPath = Paths.get("migrated_files.tsv")
        resultPath.writeLines(listOf(fields.joinToString("\t") { it.name }) + lines)
        getLogger().info("Saved information about migrated files in $resultPath")
    }
}
