package io.github.jvmusin

object DriveResetInfo {
    @JvmStatic
    fun main(args: Array<String>) {
        object : ProcessAllFiles("Resetting info about files on Google Drive", skipShortcuts = false) {
            override fun visitFile(location: FileLocation) {
                if (location.json.driveFileId == null) {
                    log("No info about this file on drive, skipping")
                } else {
                    location.updateJson { driveFileId = null; originalDriveFileId = null }
                    log("Reset info about this file (had id ${location.json.driveFileId})")
                }
            }

            override fun beforeVisitFolder(location: FolderLocation) {
                if (location.json.driveFolderId == null) {
                    log("No info about this folder on drive, skipping")
                } else {
                    location.updateJson { driveFolderId = null }
                    log("Reset info about this file (had id ${location.json.driveFolderId})")
                }
            }
        }.run()
    }
}
