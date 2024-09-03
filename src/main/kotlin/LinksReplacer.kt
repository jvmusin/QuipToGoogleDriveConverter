package io.github.jvmusin

import kenichia.quipapi.QuipThread

interface LinksReplacer {
    fun replaceLink(link: String): String?
}

class QuipUserAndDriveFileLinksReplacer(
    private val quipIdToDriveLink: Map<String, String>
) : LinksReplacer {
    private val userRepository = QuipUserRepository.INSTANCE

    override fun replaceLink(link: String): String? {
        val protocol = link.substringBefore("://")
        if (protocol == link) {
            require("quip.com" !in link.lowercase()) {
                "Found quip.com in a link without protocol: $link"
            }
            return null
        }

        val afterProtocol = link.substringAfter("$protocol://")
        if (!afterProtocol.matches(afterProtocolRegex)) return null

        val quipId = afterProtocol.substringAfter('/').takeWhile { it.isLetterOrDigit() }
        quipIdToDriveLink[quipId.lowercase()]?.let { return it }

        userRepository.getUserEmail(quipId)?.let { return "mailto:$it" }

        return null
    }

    companion object {
        private val afterProtocolRegex = Regex("([\\w-]*\\.)*quip.com/[\\w-/#]+", RegexOption.IGNORE_CASE)

        fun fromDownloaded(): QuipUserAndDriveFileLinksReplacer {
            val quipIdToDriveLinkMapping = object {
                fun extractQuipId(quipLink: String): String {
                    require(quipLink.startsWith("https://jetbrains.quip.com/")) {
                        "Wrong format for the link (does not start with https://jetbrains.quip.com/) $quipLink"
                    }
                    return quipLink.removePrefix("https://jetbrains.quip.com/").lowercase()
                }

                fun buildDriveFileLink(driveFileId: String, threadType: QuipThread.Type) = when (threadType) {
                    QuipThread.Type.DOCUMENT -> "https://docs.google.com/document/d/$driveFileId"
                    QuipThread.Type.SPREADSHEET -> "https://docs.google.com/spreadsheets/d/$driveFileId"
                    QuipThread.Type.SLIDES -> "https://drive.google.com/file/d/$driveFileId"
                    QuipThread.Type.CHAT -> error("Chats not supported")
                }

                fun buildQuipIdToDriveLinkMapping(): Map<String, String> {
                    val quipIdToDriveLinkMapping = mutableMapOf<String, String>()
                    object : ProcessAllFiles("Building Quip ID to Drive link mappings") {
                        fun save(quipId: String, link: String) {
                            require(quipId !in quipIdToDriveLinkMapping)
                            quipIdToDriveLinkMapping[quipId] = link
                        }

                        override fun visitFile(location: FileLocation) {
                            if (!location.isOriginal()) return
                            val driveFileId = location.json.driveFileId ?: return
                            val quipThread = location.json.quipThread()
                            save(
                                quipId = extractQuipId(quipThread.link),
                                link = buildDriveFileLink(driveFileId, quipThread.type)
                            )
                        }

                        override fun beforeVisitFolder(location: FolderLocation) {
                            val folderId = location.json.driveFolderId ?: return
                            save(
                                quipId = extractQuipId(location.json.quipFolder().link),
                                link = "https://drive.google.com/drive/folders/$folderId"
                            )
                        }
                    }.run()
                    return quipIdToDriveLinkMapping
                }
            }.buildQuipIdToDriveLinkMapping()
            return QuipUserAndDriveFileLinksReplacer(quipIdToDriveLinkMapping)
        }
    }
}
