package io.github.jvmusin

import io.github.jvmusin.ProcessAllFiles.FileLocation
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.writeLines

abstract class ReplaceLinksOOXML(
    private val linksReplacer: LinksReplacer
) {
    fun run() {
        object : ProcessAllFiles() {
            override fun visitFile(location: FileLocation) {
                currentFileLocation = location
                log("Replacing links")
                val updatedContent = rebuildDocument(chooseInputFilePath(location))
                if (updatedContent != null) {
                    log("Links have been replaced")
                } else {
                    log("No links have been replaced")
                }
                onFileProcessed(location, updatedContent)
            }
        }.run()

        Paths.get("suspicious_links.tsv").writeLines(susLinks)
    }

    abstract fun ProcessAllFiles.onFileProcessed(fileLocation: FileLocation, updatedContent: ByteArray?)
    abstract fun chooseInputFilePath(fileLocation: FileLocation): Path

    private fun rebuildDocument(
        documentPath: Path
    ): ByteArray? {
        if (documentPath.extension != "docx" && documentPath.extension != "xlsx") {
            return null
        }

        val os = ByteArrayOutputStream()
        documentPath.inputStream().use { fileIS ->
            ZipInputStream(fileIS).use { fileZIS ->
                ZipOutputStream(os).use { fileZOS ->
                    while (true) {
                        val e = fileZIS.nextEntry ?: break
                        val bytes = fileZIS.readBytes()
                        val withReplacedLinks = when {
                            e.name.endsWith(".rels") -> replaceLinks(
                                fileContent = bytes.decodeToString(),
                            )?.encodeToByteArray()

                            else -> null
                        }
                        val (newEntry, newContent) =
                            if (withReplacedLinks != null) ZipEntry(e.name) to withReplacedLinks
                            else e to bytes
                        fileZOS.putNextEntry(newEntry)
                        fileZOS.write(newContent)
                    }
                }
            }
        }

        return os.toByteArray()
    }

    private val userRepository = QuipUserRepository()
    private lateinit var currentFileLocation: FileLocation
    private val susLinks = mutableListOf<String>()

    private fun replaceLinks(
        fileContent: String,
    ): String? {
        val links = Jsoup.parse(fileContent).select("Relationship").map { it.attr("Target") }
        var result = fileContent
        val replacements = mutableMapOf<String, String>()

        fun String.withTarget(): String = "Target=\"$this\""
        fun String.replaceAndCheck(a: String, b: String): String = replace(a, b).also {
            require(it != this) { "Failed to replace '$a' with '$b' in '$this' (probably not found)" }
        }
        for (quipLink in links.distinct()) {
            val newLink = linksReplacer.replaceLink(quipLink)
            if (newLink != null) {
                result = result.replaceAndCheck(quipLink.withTarget(), newLink.withTarget())
                replacements[quipLink] = newLink // TODO: log it?
                continue
            }

            val author = userRepository.getUserName(currentFileLocation.json.quipThread().authorId)
            if ("quip.com" in quipLink.lowercase())
                susLinks.add("${currentFileLocation.title}\t${author}\t${currentFileLocation.json.quipThread().link}\t$quipLink")
        }
        return result.takeIf { replacements.isNotEmpty() }
    }
}
