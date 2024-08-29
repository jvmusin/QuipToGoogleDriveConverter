package io.github.jvmusin

import io.github.jvmusin.ProcessAllFiles.FileLocation
import kenichia.quipapi.QuipThread

interface LinksReplacer {
    fun replaceLink(link: String): String?
}

class QuipUserAndDriveFileLinksReplacer(
    private val quipIdToDriveLink: Map<String, String>
) : LinksReplacer {
    private val userRepository = QuipUserRepository()

    override fun replaceLink(link: String): String? {
        val protocol = link.substringBefore("://")
        if (protocol == link) {
            require("quip.com" !in link.lowercase()) {
                "Found quip.com in a link without protocol: $link"
            }
            return null
        }

        val afterProtocol = link.substringAfter("$protocol://")
        if (!afterProtocol.matches(Regex("([^.]*\\.)*quip.com/.*", RegexOption.IGNORE_CASE))) {
            require("quip.com" !in afterProtocol.lowercase()) {
                "Found quip.com in a link which doesn't head to quip.com: $link"
            }
            return null
        }
        val quipId = afterProtocol.substringAfter('/').takeWhile { it.isLetterOrDigit() }
        quipIdToDriveLink[quipId.lowercase()]?.let { return it }

        val userEmail = userRepository.getUserEmail(quipId)
        if (userEmail != null) {
            val replacement = "mailto:$userEmail"
            require(link.endsWith("quip.com/$quipId", ignoreCase = true)) {
                "Link is expected to end with $quipId, but it is $link"
            } // do not allow anything after the user id
            return replacement
        }

        return null
    }

    companion object {
        fun fromDownloaded(): QuipUserAndDriveFileLinksReplacer {
            val quipIdToDriveLinkMapping = object {
                fun buildDriveFileLink(driveFileId: String, threadType: QuipThread.Type) = when (threadType) {
                    QuipThread.Type.DOCUMENT -> "https://docs.google.com/document/d/$driveFileId"
                    QuipThread.Type.SPREADSHEET -> "https://docs.google.com/spreadsheets/d/$driveFileId"
                    QuipThread.Type.SLIDES -> "https://drive.google.com/file/d/$driveFileId"
                    QuipThread.Type.CHAT -> error("Chats not supported")
                }

                fun collectFileLocations(): List<FileLocation> {
                    val locations = mutableListOf<FileLocation>()
                    object : ProcessAllFiles("Collecting file locations") {
                        override fun visitFile(location: FileLocation) {
                            locations += location
                        }
                    }.run()
                    return locations
                }

                fun buildQuipIdToDriveLinkMapping(): Map<String, String> =
                    collectFileLocations().associate {
                        val link = it.json.quipThread().link
                        require(link.startsWith("https://jetbrains.quip.com/")) {
                            "Wrong format for the link (does not start with https://jetbrains.quip.com/) $link"
                        }
                        val quipId = link.removePrefix("https://jetbrains.quip.com/").lowercase()
                        val driveFileId = requireNotNull(it.json.driveFileId) {
                            "File is not uploaded to drive yet"
                        }
                        quipId to buildDriveFileLink(driveFileId, it.json.quipThread().type)
                    }
            }.buildQuipIdToDriveLinkMapping()
            return QuipUserAndDriveFileLinksReplacer(quipIdToDriveLinkMapping)
        }
    }
}
