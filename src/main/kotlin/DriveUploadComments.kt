package io.github.jvmusin

import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

object DriveUploadComments {
    @JvmStatic
    fun main(args: Array<String>) {
        Visitor().start()
    }

    class Visitor : ProcessAllFiles() {
        private val client = DriveClientFactory.createClient()
        private val driveFolderIdStack = ArrayDeque<String>()
        private fun currentDriveFolder() = driveFolderIdStack.lastOrNull()

        override fun visitFile(location: FileLocation) {
            val commentsFile = location.commentsPath
            if (!commentsFile.exists()) {
                log("Comments file not found, skipping")
                return
            }

            requireNotNull(location.json.driveFileId) {
                "Document is not uploaded to Google Drive"
            }

            location.json.driveCommentsFileId?.let {
                log("Skipping previously uploaded comments file with Drive id $it")
                return
            }

            log("Saving comments file on Drive")
            val driveId = client.createFile(
                parent = requireNotNull(currentDriveFolder()),
                name = location.title + " [comments]",
                sourceFile = commentsFile
            )
            location.path.apply {
                deleteIfExists()
                createNewFile(location.json.copy(driveCommentsFileId = driveId))
            }
            log("Saved comments file on Drive with id $driveId")
        }

        override fun beforeVisitFolder(location: FolderLocation) {
            driveFolderIdStack.addLast(client.getOrCreateFolder(location.title, currentDriveFolder()))
        }

        override fun afterVisitFolder(location: FolderLocation) {
            driveFolderIdStack.removeLast()
        }

        fun start() {
            val quipFolder = client.getOrCreateFolder(name = Settings.read().driveFolderName, parent = null)
            driveFolderIdStack.addLast(quipFolder)
            run()
        }
    }
}
