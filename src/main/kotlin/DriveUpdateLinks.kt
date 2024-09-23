package io.github.jvmusin

import io.github.jvmusin.ProcessAllFiles.FileLocation
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

object DriveUpdateLinks {
    @JvmStatic
    fun main(args: Array<String>) {
        object : ReplaceLinksOOXML(QuipUserAndDriveFileLinksReplacer.fromDownloaded()) {
            override fun chooseInputFilePath(fileLocation: FileLocation): Path =
                fileLocation.withCommentsAndAuthorDocumentPath

            override fun ProcessAllFiles.onFileProcessed(rebuiltDocument: RebuiltDocument<ByteArray>) {
                val fileLocation = rebuiltDocument.location
                val destinationFile = fileLocation.withCommentsAndAuthorAndLinksDocumentPath
                val content = rebuiltDocument.content ?: chooseInputFilePath(fileLocation).readBytes()
                destinationFile.writeBytes(content)
            }
        }.run()
    }
}
