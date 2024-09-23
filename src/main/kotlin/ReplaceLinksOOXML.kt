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
        val userRepository = QuipUserRepository.INSTANCE
        val unresolvedQuipLinks = mutableListOf<String>()
        val allReplacedLinks = mutableMapOf<String, String?>()

        object : ProcessAllFiles("Replacing links in documents", skipShortcuts = true) {
            override fun visitFile(location: FileLocation) {
                val rebuiltDocument = rebuildDocument(location)
                onFileProcessed(rebuiltDocument)
                allReplacedLinks += rebuiltDocument.replacedLinks
                rebuiltDocument.replacedLinks
                    .filterValues { it == null }.keys
                    .filter { "quip.com" in it.lowercase() }
                    .forEach { link ->
                        val author = userRepository.getUserName(location.json.quipThread().authorId)
                        unresolvedQuipLinks += "${location.title}\t${author}\t${location.json.quipThread().link}\t$link"
                    }
            }
        }.run()

        Paths.get("unresolved_quip_links.tsv").writeLines(unresolvedQuipLinks)
    }

    open fun ProcessAllFiles.onFileProcessed(rebuiltDocument: RebuiltDocument<ByteArray>) {}
    abstract fun chooseInputFilePath(fileLocation: FileLocation): Path

    data class RebuiltDocument<T>(val content: T?, val replacedLinks: Map<String, String?>, val location: FileLocation)

    fun rebuildDocument(
        location: FileLocation
    ): RebuiltDocument<ByteArray> {
        val documentPath = chooseInputFilePath(location)
        if (documentPath.extension != "docx" && documentPath.extension != "xlsx") {
            return RebuiltDocument(null, emptyMap(), location)
        }

        val os = ByteArrayOutputStream()
        val allReplacedLinks = mutableMapOf<String, String?>()
        documentPath.inputStream().use { fileIS ->
            ZipInputStream(fileIS).use { fileZIS ->
                ZipOutputStream(os).use { fileZOS ->
                    while (true) {
                        val e = fileZIS.nextEntry ?: break
                        val bytes = fileZIS.readBytes()
                        val withReplacedLinks = when {
                            e.name.endsWith(".rels") -> {
                                val rebuiltDoc = replaceLinksInRels(
                                    fileContent = bytes.decodeToString(),
                                    location = location
                                )
                                allReplacedLinks += rebuiltDoc.replacedLinks
                                rebuiltDoc.content?.encodeToByteArray()
                            }

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

        return RebuiltDocument(os.toByteArray(), allReplacedLinks, location)
    }

    private fun replaceLinksInRels(
        fileContent: String,
        location: FileLocation,
    ): RebuiltDocument<String> {
        val links = extractLinksFromRels(fileContent)
        var result = fileContent

        fun String.withTarget(): String = "Target=\"$this\""
        fun String.replaceAndCheck(a: String, b: String): String = replace(a, b).also {
            require(it != this) { "Failed to replace '$a' with '$b' in '$this' (probably not found)" }
        }

        val allReplacedLinks = replaceLinks(links)
        for ((link, replacement) in allReplacedLinks.filterNonNull()) {
            result = result.replaceAndCheck(link.withTarget(), replacement.withTarget())
        }
        return RebuiltDocument(result.takeIf { allReplacedLinks.isNotEmpty() }, allReplacedLinks, location)
    }

    fun extractLinksFromRels(rels: String) = Jsoup.parse(rels).select("Relationship").map { it.attr("Target") }

    fun Map<String, String?>.filterNonNull(): Map<String, String> = this
        .mapValues { it.value.orEmpty() }
        .filterValues { it.isNotEmpty() }

    fun replaceLinks(links: List<String>): Map<String, String?> {
        return links.distinct().associateWith(linksReplacer::replaceLink)
    }
}
