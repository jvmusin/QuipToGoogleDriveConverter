package io.github.jvmusin

import io.github.jvmusin.ProcessAllFiles.FileLocation
import kenichia.quipapi.QuipThread
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

// TODO: Rewrite to use ProcessAllFiles
object DriveUpdateLinks {
    private val logger = getLogger()

    class ReplaceLinksModifier(
        private val linkIdToDriveInfo: Map<String, DriveFileInfo>
    ) {
        private val updates = mutableMapOf<String, String>()
        fun process(entry: ZipEntry, content: ByteArray): Pair<ZipEntry, ByteArray> {
            val contentString = content.decodeToString()

            val (updated, replacements) = replaceLinks(contentString, linkIdToDriveInfo)
            if (replacements.isEmpty()) return entry to content
            updates += replacements
            return ZipEntry(entry.name) to updated.encodeToByteArray()
        }

        fun updates() = updates.toMap()
    }

    private fun rebuildDocument(
        file: Path,
        linkIdToDriveInfo: Map<String, DriveFileInfo>
    ): Pair<Path, Map<String, String>>? {
        if (file.extension != "docx" && file.extension != "xlsx") return null

        val os = ByteArrayOutputStream()
        var anyLinksFound = false
        val modifier = ReplaceLinksModifier(linkIdToDriveInfo)
        file.inputStream().use { fileIS ->
            ZipInputStream(fileIS).use { fileZIS ->
                ZipOutputStream(os).use { outFileZOS ->
                    while (true) {
                        val e = fileZIS.nextEntry ?: break
                        val bytes = fileZIS.readBytes()

                        val (newEntry, newContent) =
                            if (e.name.endsWith(".rels")) modifier.process(e, bytes)
                            else e to bytes
                        if (!bytes.contentEquals(newContent)) anyLinksFound = true
                        outFileZOS.putNextEntry(newEntry)
                        outFileZOS.write(newContent)
                    }
                }
            }
        }

        val destination = FileLocation(file.resolveSibling(file.nameWithoutExtension + ".json"))
            .withCommentsAndAuthorAndLinksDocumentPath
        destination.writeBytes(os.toByteArray())
        if (!anyLinksFound) return null
        return destination to modifier.updates()
    }

    data class DriveFileInfo(val id: String, val threadType: QuipThread.Type) {
        val link = when (threadType) {
            QuipThread.Type.DOCUMENT -> "docs.google.com/document/d/$id"
            QuipThread.Type.SPREADSHEET -> "docs.google.com/spreadsheets/d/$id"
            QuipThread.Type.SLIDES -> "drive.google.com/file/d/$id"
            QuipThread.Type.CHAT -> error("Chats not supported")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val fileJsons = getFileJsons()
        val linkIdToDriveInfo = fileJsons.entries.associate {
            val thread = it.value.json.quip.getAsJsonObject("thread")
            val type = thread.getAsJsonPrimitive("type").asString
            val link = thread.getAsJsonPrimitive("link").asString
            val threadType = QuipThread.Type.valueOf(type.uppercase())
            require(link.startsWith("https://jetbrains.quip.com/")) {
                "Wrong format for the link (does not start with https://jetbrains.quip.com/) $link"
            }
            val linkId = link.removePrefix("https://jetbrains.quip.com/").lowercase()
            val driveId = requireNotNull(it.value.json.driveFileId) {
                "File is not uploaded to drive yet"
            }
            linkId to DriveFileInfo(driveId, threadType)
        }

        val totalCount = fileJsons.size
        for ((i, entry) in fileJsons.entries.withIndex()) {
            val (_, fileJson) = entry
            currentFileLocation = fileJson
            val filePath = fileJson.documentPath
            val prefix = "${i + 1}/$totalCount $filePath"
            val updatedFileEntry = rebuildDocument(filePath, linkIdToDriveInfo)
            if (updatedFileEntry == null) {
                logger.info("$prefix -- No links found, skipping")
                continue
            }
            fileJsons[entry.key]!!.withCommentsAndAuthorAndLinksDocumentPath
                .writeBytes(updatedFileEntry.first.readBytes())
            updatedFileEntry.second.forEach { (from, to) ->
                logger.info("$prefix -- Made replacement $from -> $to")
            }

            val driveClient = DriveClientFactory.createClient()
            logger.info("$prefix -- Updating file")
            driveClient.updateFile(fileJson.json.driveFileId!!, updatedFileEntry.first)
            logger.info("$prefix -- File updated")
        }

        Paths.get("suspicious_links.tsv").writeLines(susLinks)
    }

    fun ByteArray.contains(other: ByteArray, thisOffset: Int): Boolean {
        require(thisOffset + other.size <= size)
        return other.indices.all { other[it] == this[thisOffset + it] }
    }

    private val userRepository = QuipUserRepository()
    private lateinit var currentFileLocation: FileLocation
    private val susLinks = mutableListOf<String>()

    private fun replaceLinks(
        fileContent: String,
        linkIdToDriveInfo: Map<String, DriveFileInfo>
    ): Pair<String, Map<String, String>> {
        val links = Jsoup.parse(fileContent).select("relationship").map { it.attr("target") }
        var result = fileContent
        val replacements = mutableMapOf<String, String>()
        val linksToQuip = links.filter { link ->
            val protocol = link.substringBefore("://")
            if (protocol == link) {
                // protocol not found
                require("quip.com" !in link.lowercase()) {
                    "Protocol in a link to quip.com not found"
                }
                return@filter false
            }
            val afterProtocol = link.substring(protocol.length)
            afterProtocol.matches(Regex("([^.]*\\.)?quip.com/.*", RegexOption.IGNORE_CASE))
        }.distinct()

        fun String.withTarget(): String = "Target=\"$this\""
        fun String.replaceAndCheck(a: String, b: String): String = replace(a, b).also { it -> require(it != this) }
        for (link in linksToQuip) {
            val relativePath = link.substringAfter("://").substringAfter('/')
            val linkId = relativePath.takeWhile { it.isLetterOrDigit() }.lowercase()

            val driveFileInfo = linkIdToDriveInfo[linkId]
            if (driveFileInfo != null) {
                val replacement = driveFileInfo.link // TODO: check that there is nothing after the id
                result = result.replaceAndCheck(link.withTarget(), replacement.withTarget())
                replacements[link] = replacement
                continue
            }

            val userEmail = userRepository.getUserEmail(linkId)
            if (userEmail != null) {
                val replacement = "mailto:$userEmail"
                require(link.endsWith("quip.com/$linkId")) // do not allow anything after the user id
                result = result.replaceAndCheck(link.withTarget(), replacement.withTarget())
                replacements[link] = replacement
                continue
            }

            val author = userRepository.getUserName(currentFileLocation.json.quipThread().authorId)
            susLinks.add("${currentFileLocation.title}\t${author}\t${currentFileLocation.json.quipThread().link}\t$link")
            println("Found a link to something unknown: $link")
        }
        return result to replacements
    }

    private fun getFileJsons(): Map<Path, FileLocation> {
        val fileJsons = hashMapOf<Path, FileLocation>()
        val visitor = object : ProcessAllFiles("Collecting file jsons") {
            override fun visitFile(location: FileLocation) {
                fileJsons[location.path] = location
            }
        }
        visitor.run()
        return fileJsons
    }
}
