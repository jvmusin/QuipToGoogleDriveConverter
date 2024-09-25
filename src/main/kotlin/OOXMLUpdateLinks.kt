package io.github.jvmusin

import io.github.jvmusin.QuipDownloadComments.CommentsThread

object OOXMLUpdateLinks {
    private fun String.replacePrefix(oldPrefix: String, newPrefix: String): String =
        if (startsWith(oldPrefix)) newPrefix + removePrefix(oldPrefix) else this

    fun updateLinksInComments(
        location: ProcessAllFiles.FileLocation,
        replaceMailtoWithAt: Boolean = false
    ): List<CommentsThread> =
        requireNotNull(location.json.quipComments).map { thread ->
            val comments = thread.comments.map { comment ->
                val linksToReplace = findLinksInText(comment.text)
                    .mapNotNull { link ->
                        val replacement = replaceLinkWithMaybeDroppingSomeSuffix(link)
                        if (replacement == null) {
                            val author = QuipUserRepository.INSTANCE.getUserName(location.json.quipThread().authorId)
                            appendUnresolvedLink(
                                documentTitle = location.title,
                                author = author.orEmpty(),
                                linkToADocument = location.json.quipThread().link,
                                unresolvedLink = link
                            )
                        }
                        replacement
                    }
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

    private val linksReplacer = QuipUserAndDriveFileLinksReplacer.fromDownloaded()

    private fun replaceLinkWithMaybeDroppingSomeSuffix(link: String): Pair<String, String>? {
        val replaced = linksReplacer.replaceLink(link)
        return when {
            replaced != null -> link to replaced
            link.last().isLetterOrDigit() -> replaceLinkWithMaybeDroppingSomeSuffix(link.dropLast(1))
            else -> null
        }
    }

    private fun findLinksInText(text: String): List<String> {
        val linkRegex = Regex("https://([\\w-]*\\.)*quip.com/[\\w-/#]+", RegexOption.IGNORE_CASE)

        // TODO: Try look-ahead
        return text.split(Regex("https://")).drop(1).map { "https://$it" }
            .flatMap { s -> linkRegex.findAll(s).map { it.value } } // no links found
    }
}