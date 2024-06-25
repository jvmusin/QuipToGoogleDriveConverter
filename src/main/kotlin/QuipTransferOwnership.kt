package io.github.jvmusin

import java.nio.file.FileVisitResult
import java.nio.file.Paths
import kotlin.io.path.readLines
import kotlin.io.path.visitFileTree

object QuipTransferOwnership {
    private val logger = getLogger()

    @JvmStatic
    fun main(args: Array<String>) {
        val quipIdToDriveEmail = Paths.get("quipId_to_driveEmail.txt").readLines().associate {
            val (quipId, driveEmail) = it.split(" ")
            quipId to driveEmail
        }

        val driveClient = DriveClientFactory.createClient()

        downloadedPath.visitFileTree {
            onVisitFile { file, attributes ->
                file.readFileJson()?.let { fileJson ->
                    val quipAuthorId = fileJson.quip
                        .getAsJsonObject("thread")
                        .getAsJsonPrimitive("author_id")
                        .asString
                    val quipAuthor = QuipUserInfo.getById(quipAuthorId)

                    val driveEmail = quipIdToDriveEmail[quipAuthorId]

                    if (driveEmail == null) {
                        logger.info("$file --- No author email for $quipAuthor found, skipping")
                    } else {
                        logger.info("$file --- Transferring ownership to email $driveEmail")
                        val driveInfo = requireNotNull(fileJson.driveInfo) {
                            "$file --- Google Drive info not found, maybe file was not uploaded to Drive yet"
                        }
                        driveClient.transferOwnership(driveInfo.id, driveEmail)
                        logger.info("$file --- Transferred ownership to email $driveEmail")
                    }
                }
                FileVisitResult.CONTINUE
            }
        }
    }
}
