package io.github.jvmusin

import com.google.gson.JsonArray
import java.nio.file.Paths
import kotlin.io.path.readText

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
        private val afterProtocolRegex = Regex("([\\w-]*\\.)*quip.com/.+", RegexOption.IGNORE_CASE)

        fun fromDownloaded(): QuipUserAndDriveFileLinksReplacer {
            /**
             * Returns mapping from one quip id to another one.
             *
             * Useful when multiple ids are assigned to the same quip document or folder.
             */
            fun readCustomIdReplacements(): Map<String, String> {
                return gson().fromJson(
                    Paths.get("extra_quip_id_replacements.jsonc").readText(), JsonArray::class.java
                ).associate { replacement ->
                    val quipId = replacement.asJsonObject.getAsJsonPrimitive("quipId").asString!!
                    val realQuipId = replacement.asJsonObject.getAsJsonPrimitive("realQuipId").asString!!
                    quipId to realQuipId
                }
            }

            val quipIdToDriveLinkMapping = object {
                fun extractQuipId(quipLink: String): String {
                    require(quipLink.startsWith("https://jetbrains.quip.com/")) {
                        "Wrong format for the link (does not start with https://jetbrains.quip.com/) $quipLink"
                    }
                    return quipLink.removePrefix("https://jetbrains.quip.com/").lowercase()
                }

                fun buildQuipIdToDriveLinkMapping(): Map<String, String> {
                    val quipIdToDriveLinkMapping = mutableMapOf<String, String>()
                    object : ProcessAllFiles("Building Quip ID to Drive link mappings", skipShortcuts = true) {
                        fun save(quipId: String, link: String) {
                            val oldValue = quipIdToDriveLinkMapping[quipId]
                            if (oldValue == null) quipIdToDriveLinkMapping[quipId] = link
                            else require(oldValue == link)
                        }

                        override fun visitFile(location: FileLocation) {
                            val driveFileId = location.json.driveFileId ?: return
                            val quipThread = location.json.quipThread()
                            val link = buildDriveFileLink(driveFileId, quipThread.type)
                            save(
                                quipId = extractQuipId(quipThread.link),
                                link = link
                            )
                            save(
                                quipId = quipThread.id.lowercase(),
                                link = link
                            )
                        }

                        override fun beforeVisitFolder(location: FolderLocation) {
                            val folderId = location.json.driveFolderId ?: return
                            val quipFolder = location.json.quipFolder()
                            save(
                                quipId = extractQuipId(quipFolder.link),
                                link = "https://drive.google.com/drive/folders/$folderId"
                            )
                            save(
                                quipId = quipFolder.id.lowercase(),
                                link = "https://drive.google.com/drive/folders/$folderId"
                            )
                        }
                    }.run()
                    return quipIdToDriveLinkMapping
                }
            }.buildQuipIdToDriveLinkMapping()
            val extraMappings = readCustomIdReplacements().mapNotNull { (quipId, realQuipId) ->
                val replacement = quipIdToDriveLinkMapping[realQuipId.lowercase()] ?: run {
                    getLogger().warning("Not found mapping for id $realQuipId")
                    return@mapNotNull null
                }
                quipId.lowercase() to replacement
            }.toMap()
            return QuipUserAndDriveFileLinksReplacer(quipIdToDriveLinkMapping + extraMappings)
        }
    }
}
