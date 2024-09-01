package io.github.jvmusin

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.writeLines

object OOXMLFindLinks {
    @JvmStatic
    fun main(args: Array<String>) {
        val userRepository = QuipUserRepository.INSTANCE
        val lines = mutableListOf<String>()
        val allLinksInComments = mutableListOf<String>()
        object : ProcessAllFiles() {
            override fun visitFile(location: FileLocation) {
                if (!location.isOriginal()) return
                val unresolvedLinksInComments = location.json.quipComments!!
                    .flatMap { t -> t.comments.map { c -> c.text } }
                    .flatMap(::findLinksInText)
                    .filter { replaceLinkWithMaybeDroppingSomeSuffix(it) == null }
                allLinksInComments += unresolvedLinksInComments
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

    fun replaceLinkWithMaybeDroppingSomeSuffix(link: String): Pair<String, String>? {
        val replaced = linksReplacer.replaceLink(link)
        return when {
            replaced != null -> link to replaced
            link.last().isLetterOrDigit() -> replaceLinkWithMaybeDroppingSomeSuffix(link.dropLast(1))
            else -> null
        }
    }

    fun findLinksInText(text: String): List<String> {
        val linkRegex = Regex("https://([\\w-]*\\.)*quip.com/[\\w-/#]+", RegexOption.IGNORE_CASE)

        // TODO: Try look-ahead
        return text.split(Regex("https://")).drop(1).map { "https://$it" }
            .flatMap { s -> linkRegex.findAll(s).map { it.value } } // no links found
    }
}