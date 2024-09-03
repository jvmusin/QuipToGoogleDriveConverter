package io.github.jvmusin

import io.github.jvmusin.QuipDownloadComments.CommentsThread
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeLines

object OOXMLUpdateLinks {
    @JvmStatic
    fun main(args: Array<String>) {
        val lines = mutableListOf<String>()
        object : ProcessAllFiles(skipShortcuts = true) {
            override fun visitFile(location: FileLocation) {
                location.updateJson { updatedQuipComments = updateLinks(location.json.quipComments!!) }

                val linksInRels = relsFinder.rebuildDocument(location)
                val content = linksInRels.content ?: location.documentPath.readBytes()
                location.withCommentsAndAuthorAndLinksDocumentPath.writeBytes(content)
            }
        }.run()
        Paths.get("unresolved_links.tsv").writeLines(lines)
    }

    private fun String.replacePrefix(oldPrefix: String, newPrefix: String): String =
        if (startsWith(oldPrefix)) newPrefix + removePrefix(oldPrefix) else this

    fun updateLinks(threads: List<CommentsThread>, replaceMailtoWithAt: Boolean = false): List<CommentsThread> =
        threads.map { thread ->
            val comments = thread.comments.map { comment ->
                val linksToReplace = findLinksInText(comment.text)
                    .mapNotNull(::replaceLinkWithMaybeDroppingSomeSuffix)
                    .sortedByDescending { it.first.length } // longer links first
                val newText = linksToReplace.fold(comment.text) { text, (oldLink, newLink) ->
                    val replacement = when {
                        replaceMailtoWithAt -> newLink.replacePrefix("mailto:", "@")
                        else -> newLink
                    }
                    text.replace(oldLink, replacement)
                }
                comment.copy(text = newText)
            }
            thread.copy(comments = comments)
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