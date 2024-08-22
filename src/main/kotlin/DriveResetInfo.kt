package io.github.jvmusin

object DriveResetInfo {
    @JvmStatic
    fun main(args: Array<String>) {
        object : ProcessAllFiles("resetting info about files on Google Drive") {
            override fun visitFile(location: FileLocation) {
                if (location.json.driveFileId == null) {
                    log("No info about this file on drive, skipping")
                } else {
                    location.updateJson { driveFileId = null }
                    log("Reset info about this file (had id ${location.json.driveFileId})")
                }
            }
        }.run()
    }
}
