package io.github.jvmusin

import org.jsoup.Jsoup
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.writeLines

object OOXMLFindLinks {
    @JvmStatic
    fun main(args: Array<String>) {
        val userRepository = QuipUserRepository()
        val lines = mutableListOf<String>()
        object : ProcessAllFiles() {
            override fun visitFile(location: FileLocation) {
                if (!location.isOriginal()) return
                val unresolvedLinksInComments = location.json.quipComments!!
                    .flatMap { t ->
                        t.comments.map { c ->
                            c.text
                        }
                    }
                    .flatMap(::findLinksInText)
                    .filter { linksReplacer.replaceLink(it) == null }
                val linksInRels = relsFinder.rebuildDocument(location)
                val unresolvedLinks =
                    linksInRels.replacedLinks.filter {
                        it.value == null && it.key.contains(
                            "quip.com",
                            ignoreCase = true
                        ) && false // only take comments for now
                    }.keys + unresolvedLinksInComments
                unresolvedLinks.map { link ->
                    val author = userRepository.getUserName(location.json.quipThread().authorId)
                    "${location.title}\t${author}\t${location.json.quipThread().link}\t$link"
                }.let { lines.addAll(it) }
            }
        }.run()
        Paths.get("unresolved_links.tsv").writeLines(lines)
    }


    val linksReplacer = QuipUserAndDriveFileLinksReplacer.fromDownloaded()
    val relsFinder = object : ReplaceLinksOOXML(linksReplacer) {
        override fun chooseInputFilePath(fileLocation: ProcessAllFiles.FileLocation): Path {
            return fileLocation.documentPath
        }
    }

    fun findLinksInText(text: String): List<String> {
        val linkRegex = Regex("\\S*quip.com/\\S+")
        return linkRegex.findAll(text).map { it.value }.toList()
    }

    fun findLinksInRels(rels: String): List<String> {
        return Jsoup.parse(rels).select("Relationship")
            .map { it.attr("Target") }
            .filter { "quip.com" in it.lowercase() }
    }

    fun findLinksInRels(): List<String> {
        val links = mutableListOf<String>()
        val replacer = object : ReplaceLinksOOXML(QuipUserAndDriveFileLinksReplacer.fromDownloaded()) {
            override fun chooseInputFilePath(fileLocation: ProcessAllFiles.FileLocation): Path {
                return fileLocation.documentPath
            }
        }
        return links
    }
}