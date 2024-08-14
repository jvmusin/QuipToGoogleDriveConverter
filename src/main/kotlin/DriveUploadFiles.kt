package io.github.jvmusin

import kotlin.io.path.deleteExisting

object DriveUploadFiles {
    @JvmStatic
    fun main(args: Array<String>) {
        Visitor().start()
    }

    private class Visitor : ProcessAllFiles() {
        private val client = DriveClientFactory.createClient()
        private val driveFolderIdStack = ArrayDeque<String>()
        private fun currentDriveFolder() = driveFolderIdStack.lastOrNull()

        override fun visitFile(location: FileLocation) {
            location.json.driveFileId?.let {
                log("Skipping previously uploaded file with Drive id $it")
                return
            }

            log("Saving file on Google Drive")
            val driveId = client.createFile(
                parent = requireNotNull(currentDriveFolder()),
                name = location.title,
                sourceFile = location.documentPath
            )
            location.path.apply {
                deleteExisting()
                createNewFile(location.json.copy(driveFileId = driveId))
            }
            log("File saved on Google Drive with id $driveId")
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
