package io.github.jvmusin

import com.google.gson.JsonObject
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.visitFileTree

object QuipTransferOwnership {
    private val logger = getLogger()
    private val extraEmailsPath = Paths.get("quip_emails_extra.json")

    data class QuipUserResource(
        val id: String,
        val emails: List<String>
    )

    private fun getEmailByUserId(): Map<String, String> {
        val userIdToEmail = gson()
            .fromJson(Paths.get("quip_users_scim.json").readText(), JsonObject::class.java)
            .getAsJsonArray("Resources")
            .map { gson().fromJson(it, QuipUserResource::class.java) }
            .filter { it.emails.isNotEmpty() }
            .associate { it.id to it.emails.first() }
        val extraUserIdToEmail = if (extraEmailsPath.exists()) {
            gson()
                .fromJson(extraEmailsPath.readText(), JsonObject::class.java)
                .asJsonObject
                .entrySet()
                .associate { it.key to it.value.asString }
        } else {
            emptyMap()
        }
        return userIdToEmail + extraUserIdToEmail
    }

    data class CandidateToUpdateOwnership(
        val file: Path,
        val fileJson: FileJson,
        val driveEmail: String,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        setupQuipClient()

        val userIdToEmail = getEmailByUserId()

        val notFoundEmailUsers = mutableSetOf<QuipUserInfo>()
        val candidates = mutableListOf<CandidateToUpdateOwnership>()
        iterateOverAllFileJsons { file, fileJson ->
            val quipAuthorId = fileJson.quip
                .getAsJsonObject("thread")
                .getAsJsonPrimitive("author_id")
                .asString
            val quipAuthor = QuipUserInfo.getById(quipAuthorId)

            val driveEmail = userIdToEmail[quipAuthorId]
            if (driveEmail == null) notFoundEmailUsers += quipAuthor
            else candidates += CandidateToUpdateOwnership(file, fileJson, driveEmail)
        }
        if (notFoundEmailUsers.isNotEmpty()) {
            for (userInfo in notFoundEmailUsers) {
                logger.warning("Not found email for user $userInfo. Add this user to $extraEmailsPath")
            }
            return
        }

        logger.info("Starting transferring ownership")
        val driveClient = DriveClientFactory.createClient()

        for ((file, fileJson, driveEmail) in candidates) {
            logger.info("$file --- Transferring ownership to email $driveEmail")
            val driveInfo = requireNotNull(fileJson.driveInfo) {
                "$file --- Google Drive info not found, maybe file was not uploaded to Google Drive yet"
            }
            driveClient.transferOwnership(driveInfo.id, driveEmail)
            logger.info("$file --- Transferred ownership to email $driveEmail")
        }

        logger.info("Finished transferring ownership")
    }

    private inline fun iterateOverAllFileJsons(crossinline block: (file: Path, fileJson: FileJson) -> Unit) {
        downloadedPath.visitFileTree {
            onVisitFile { file, _ ->
                file.readFileJson()?.let { fileJson -> block(file, fileJson) }
                FileVisitResult.CONTINUE
            }
        }
    }
}
