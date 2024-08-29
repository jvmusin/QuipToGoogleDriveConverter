package io.github.jvmusin

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
                sourceFile = location.withCommentsAndAuthorDocumentPath
            )
            location.updateJson { driveFileId = driveId }
            log("File saved on Google Drive with id $driveId")
        }

        override fun beforeVisitFolder(location: FolderLocation) {
            // TODO: Maybe not use existing folders but always create a new one?
            val folderId = location.json.driveFolderId ?: run {
                client.getOrCreateFolder(location.title, currentDriveFolder()).also { folderId ->
                    location.updateJson { driveFolderId = folderId }
                }
            }
            driveFolderIdStack.addLast(folderId)
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
