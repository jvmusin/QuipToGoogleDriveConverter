package io.github.jvmusin

object DriveGenerateIds {
    @JvmStatic
    fun main(args: Array<String>) {
        val fileLocations = mutableListOf<ProcessAllFiles.FileLocation>()
        val folderLocations = mutableListOf<ProcessAllFiles.FolderLocation>()
        object : ProcessAllFiles("Collecting file locations") {
            override fun visitFile(location: FileLocation) {
                if (location.json.driveFileId == null)
                    fileLocations += location
            }

            override fun beforeVisitFolder(location: FolderLocation) {
                if (location.json.driveFolderId == null)
                    folderLocations += location
            }
        }.run()
        val ids = DriveClientFactory.createClient().generateIds(fileLocations.size + folderLocations.size).iterator()
        fileLocations.forEach { it.updateJson { driveFileId = ids.next() } }
        folderLocations.forEach { it.updateJson { driveFolderId = ids.next() } }
    }
}
