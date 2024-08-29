package io.github.jvmusin

import io.github.jvmusin.ProcessAllFiles.FileLocation
import kenichia.quipapi.QuipThread
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.inputStream
import kotlin.io.path.writeBytes
import kotlin.io.path.writeLines

object DriveUpdateLinks {
    class ReplaceLinksModifier(
        private val quipIdToDriveLink: Map<String, String>
    ) {
        private val updates = mutableMapOf<String, String>()
        fun process(entry: ZipEntry, content: ByteArray): Pair<ZipEntry, ByteArray> {
            val contentString = content.decodeToString()

            val (updated, replacements) = replaceLinks(contentString, quipIdToDriveLink)
            if (replacements.isEmpty()) return entry to content
            updates += replacements
            return ZipEntry(entry.name) to updated.encodeToByteArray()
        }

        fun updates() = updates.toMap()

        class Factory(private val quipIdToDriveLink: Map<String, String>) {
            fun create(): ReplaceLinksModifier = ReplaceLinksModifier(quipIdToDriveLink)
        }
    }

    private fun rebuildDocument(
        location: FileLocation,
        modifierFactory: ReplaceLinksModifier.Factory
    ): Map<String, String> {
        if (location.type != QuipFileType.Docx && location.type != QuipFileType.Spreadsheet) return emptyMap()

        val os = ByteArrayOutputStream()
        val modifier = modifierFactory.create()
        location.withCommentsAndAuthorDocumentPath.inputStream().use { fileIS ->
            ZipInputStream(fileIS).use { fileZIS ->
                ZipOutputStream(os).use { fileZOS ->
                    while (true) {
                        val e = fileZIS.nextEntry ?: break
                        val bytes = fileZIS.readBytes()
                        val (newEntry, newContent) =
                            if (e.name.endsWith(".rels")) modifier.process(e, bytes)
                            else e to bytes
                        fileZOS.putNextEntry(newEntry)
                        fileZOS.write(newContent)
                    }
                }
            }
        }

        location.withCommentsAndAuthorAndLinksDocumentPath.writeBytes(os.toByteArray())
        return modifier.updates()
    }

    private fun buildDriveFileLink(driveFileId: String, threadType: QuipThread.Type) = when (threadType) {
        QuipThread.Type.DOCUMENT -> "https://docs.google.com/document/d/$driveFileId"
        QuipThread.Type.SPREADSHEET -> "https://docs.google.com/spreadsheets/d/$driveFileId"
        QuipThread.Type.SLIDES -> "https://drive.google.com/file/d/$driveFileId"
        QuipThread.Type.CHAT -> error("Chats not supported")
    }

    private fun collectFileLocations(): List<FileLocation> {
        val locations = mutableListOf<FileLocation>()
        object : ProcessAllFiles("Collecting file locations") {
            override fun visitFile(location: FileLocation) {
                locations += location
            }
        }.run()
        return locations
    }

    private fun buildQuipIdToDriveLinkMapping(): Map<String, String> =
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

    private fun buildQuipIdToDriveLinkModifierFactory(): ReplaceLinksModifier.Factory =
        ReplaceLinksModifier.Factory(buildQuipIdToDriveLinkMapping())

    @JvmStatic
    fun main(args: Array<String>) {
        val modifierFactory = buildQuipIdToDriveLinkModifierFactory()
        val driveClient = DriveClientFactory.createClient()

        object : ProcessAllFiles() {
            override fun visitFile(location: FileLocation) {
                currentFileLocation = location
                val updatedFileEntry = rebuildDocument(location, modifierFactory)
                if (updatedFileEntry.isEmpty()) {
                    log("No links found, skipping")
                    return
                }

                updatedFileEntry.forEach { (from, to) ->
                    log("Made replacement $from → $to")
                }

                log("Updating file on Google Drive")
                driveClient.updateFile(
                    fileId = requireNotNull(location.json.driveFileId) {
                        "File is not uploaded to Google Drive yet"
                    },
                    sourceFile = location.withCommentsAndAuthorAndLinksDocumentPath
                )
                log("File on Google Drive updated")
            }
        }.run()

        Paths.get("suspicious_links.tsv").writeLines(susLinks)
    }

    private val userRepository = QuipUserRepository()
    private lateinit var currentFileLocation: FileLocation
    private val susLinks = mutableListOf<String>()

    private fun replaceLinks(
        fileContent: String,
        quipIdToDriveLink: Map<String, String>
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
            afterProtocol.matches(Regex("([^.]*\\.)*quip.com/.*", RegexOption.IGNORE_CASE))
        }.distinct()

        fun String.withTarget(): String = "Target=\"$this\""
        fun String.replaceAndCheck(a: String, b: String): String = replace(a, b).also {
            require(it != this) { "Failed to replace '$a' with '$b' in '$this' (probably not found)" }
        }
        for (quipLink in linksToQuip) {
            val relativePath = quipLink.substringAfter("://").substringAfter('/')
            val quipId = relativePath.takeWhile { it.isLetterOrDigit() }.lowercase()

            val driveLink = quipIdToDriveLink[quipId]
            if (driveLink != null) {
                result = result.replaceAndCheck(quipLink.withTarget(), driveLink.withTarget())
                replacements[quipLink] = driveLink
                continue
            }

            val userEmail = userRepository.getUserEmail(quipId)
            if (userEmail != null) {
                val replacement = "mailto:$userEmail"
                require(quipLink.endsWith("quip.com/$quipId", ignoreCase = true)) {
                    "Link is expected to end with $quipId, but it is $quipLink"
                } // do not allow anything after the user id
                result = result.replaceAndCheck(quipLink.withTarget(), replacement.withTarget())
                replacements[quipLink] = replacement
                continue
            }

            val author = userRepository.getUserName(currentFileLocation.json.quipThread().authorId)
            susLinks.add("${currentFileLocation.title}\t${author}\t${currentFileLocation.json.quipThread().link}\t$quipLink")
            println("Found a link to something unknown: $quipLink")
        }
        return result to replacements
    }
}
