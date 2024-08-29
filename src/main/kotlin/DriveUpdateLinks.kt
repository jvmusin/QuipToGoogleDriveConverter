package io.github.jvmusin

import io.github.jvmusin.ProcessAllFiles.FileLocation
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.copyTo
import kotlin.io.path.inputStream
import kotlin.io.path.writeBytes
import kotlin.io.path.writeLines

object DriveUpdateLinks {
    private fun rebuildDocument(
        location: FileLocation,
        linkReplacer: QuipUserAndDriveFileLinkReplacer
    ): Boolean {
        val fromPath = location.withCommentsAndAuthorDocumentPath
        val toPath = location.withCommentsAndAuthorAndLinksDocumentPath

        if (location.type != QuipFileType.Docx && location.type != QuipFileType.Spreadsheet) {
            fromPath.copyTo(toPath)
            return false
        }

        val os = ByteArrayOutputStream()
        var anyUpdates = false
        fromPath.inputStream().use { fileIS ->
            ZipInputStream(fileIS).use { fileZIS ->
                ZipOutputStream(os).use { fileZOS ->
                    while (true) {
                        val e = fileZIS.nextEntry ?: break
                        val bytes = fileZIS.readBytes()
                        val withReplacedLinks = replaceLinks(bytes.decodeToString(), linkReplacer)?.encodeToByteArray()
                        if (withReplacedLinks != null) anyUpdates = true
                        val (newEntry, newContent) =
                            if (withReplacedLinks != null) ZipEntry(e.name) to withReplacedLinks
                            else e to bytes
                        fileZOS.putNextEntry(newEntry)
                        fileZOS.write(newContent)
                    }
                }
            }
        }

        toPath.writeBytes(os.toByteArray())
        return anyUpdates
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val linkReplacer = QuipUserAndDriveFileLinkReplacer.fromDownloaded()
        val driveClient = DriveClientFactory.createClient()

        object : ProcessAllFiles() {
            override fun visitFile(location: FileLocation) {
                currentFileLocation = location
                val anyUpdates = rebuildDocument(location, linkReplacer)
                if (!anyUpdates) {
                    log("No links found, skipping")
                    return
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
        linkReplacer: QuipUserAndDriveFileLinkReplacer
    ): String? {
        val links = Jsoup.parse(fileContent).select("relationship").map { it.attr("target") }
        var result = fileContent
        val replacements = mutableMapOf<String, String>()

        fun String.withTarget(): String = "Target=\"$this\""
        fun String.replaceAndCheck(a: String, b: String): String = replace(a, b).also {
            require(it != this) { "Failed to replace '$a' with '$b' in '$this' (probably not found)" }
        }
        for (quipLink in links) {
            val newLink = linkReplacer.replaceLink(quipLink)
            if (newLink != null) {
                result = result.replaceAndCheck(quipLink.withTarget(), newLink.withTarget())
                replacements[quipLink] = newLink // TODO: log it
                continue
            }

            val author = userRepository.getUserName(currentFileLocation.json.quipThread().authorId)
            susLinks.add("${currentFileLocation.title}\t${author}\t${currentFileLocation.json.quipThread().link}\t$quipLink")
            println("Found a link to something unknown: $quipLink")
        }
        if (replacements.isEmpty()) return null
        return result
    }
}
