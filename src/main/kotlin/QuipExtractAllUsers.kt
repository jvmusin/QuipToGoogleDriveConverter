package io.github.jvmusin

import java.nio.file.Paths
import kotlin.io.path.writeLines

object QuipExtractAllUsers {
    private val logger = getLogger()

    @JvmStatic
    fun main(args: Array<String>) {
        logger.info("Started extracting all users into tsv")
        val allUsers = mutableSetOf<String>()
        object : ProcessAllFiles() {
            override fun visitFile(location: FileLocation) {
                allUsers.add(location.json.quipThread().authorId)
            }
        }.run()
        val userRepository = QuipUserRepository.INSTANCE
        val lines = allUsers.map { userId ->
            val parts = listOf(
                userId,
                "https://quip.com/$userId",
                userRepository.getUserName(userId),
                userRepository.getUserEmail(userId).orEmpty(),
            )
            parts.joinToString("\t")
        }
        val destination = Paths.get("all_users.tsv")
        destination.writeLines(lines)
        logger.info("${allUsers.size} users were extracted to $destination")
    }
}
