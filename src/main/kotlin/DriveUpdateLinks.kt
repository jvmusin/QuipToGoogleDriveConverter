package io.github.jvmusin

import io.github.jvmusin.ProcessAllFiles.FileLocation
import java.nio.file.Path
import kotlin.io.path.writeBytes

object DriveUpdateLinks {
    @JvmStatic
    fun main(args: Array<String>) {
        val driveClient = DriveClientFactory.createClient()
        object : ReplaceLinksOOXML(QuipUserAndDriveFileLinksReplacer.fromDownloaded()) {
            override fun chooseInputFilePath(fileLocation: FileLocation): Path =
                fileLocation.withCommentsAndAuthorDocumentPath

            override fun ProcessAllFiles.onLinksReplaced(fileLocation: FileLocation, updatedContent: ByteArray) {
                val destinationFile = fileLocation.withCommentsAndAuthorAndLinksDocumentPath
                destinationFile.writeBytes(updatedContent)
                log("Updating file on Google Drive")
                driveClient.updateFile(
                    fileId = requireNotNull(fileLocation.json.driveFileId) {
                        "File is not uploaded to Google Drive yet"
                    },
                    sourceFile = destinationFile
                )
                log("File on Google Drive updated")
            }
        }
    }
}
