package io.github.jvmusin

object DriveUploadFiles {
    @JvmStatic
    fun main(args: Array<String>) {
        Visitor().start()
    }

    private class Visitor : ProcessAllFiles() {
        private val client = DriveClientFactory.createClient()
        private val driveFolderIdStack = mutableListOf<String>()
        private val foundIds = mutableSetOf<String>()
        private fun currentDriveFolder() = driveFolderIdStack.lastOrNull()

        override fun visitFile(location: FileLocation) {
            val driveFileId = location.json.driveFileId!!
            if (driveFileId in foundIds) {
                log("File already uploaded, skipping")
                return
            }

            log("Uploading file on Google Drive")
            client.createFile(
                parent = requireNotNull(currentDriveFolder()),
                name = location.title,
                sourceFile = location.withCommentsAndAuthorAndLinksDocumentPath,
                id = driveFileId
            )
            foundIds += driveFileId
            log("File successfully uploaded on Google Drive")
        }

        override fun beforeVisitFolder(location: FolderLocation) {
            val driveFolderId = location.json.driveFolderId!!
            if (foundIds.add(driveFolderId)) {
                log("Creating folder on Google Drive")
                client.createFolder(location.title, driveFolderId, currentDriveFolder())
                log("Folder created")
            } else {
                log("Folder already exists on Google Drive, requesting its contents' ids")
                val ids = client.listFolderContents(driveFolderId).map { it.id }
                foundIds += ids
                log("Saved ${ids.size} ids from a folder")
            }
            driveFolderIdStack.add(driveFolderId)
        }

        override fun afterVisitFolder(location: FolderLocation) {
            driveFolderIdStack.removeLast()
        }

        fun start() {
            val rootFolderId: String? = null
            foundIds.clear()
            foundIds.addAll(client.listFolderContents(rootFolderId).map { it.id })
            run()
        }
    }
}
