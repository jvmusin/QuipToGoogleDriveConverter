package io.github.jvmusin

import kenichia.quipapi.QuipMessage
import kenichia.quipapi.QuipThread
import org.jsoup.Jsoup
import java.nio.file.FileVisitResult
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.io.path.deleteIfExists
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.visitFileTree
import kotlin.io.path.writeText

object QuipDownloadComments {
    private val logger = getLogger()

    @JvmStatic
    fun main(args: Array<String>) {
        setupQuipClient()
        downloadedPath.visitFileTree {
            onVisitFile { file, _ ->
                file.readFileJson()?.let { fileJson ->
                    val thread = fileJson.quipThread()
                    val html = thread.html ?: withBackoff { QuipThread.getThread(thread.id).html }
                    val page = Jsoup.parse(html)
                    val recentMessages = withBackoff {
                        QuipMessage.getRecentMessages(
                            thread.id,
                            100,
                            null,
                            null,
                            null,
                            QuipThread.SortedBy.NONE,
                            null
                        ).reversedArray()
                    }
                    if (recentMessages.isEmpty()) {
                        logger.info("${thread.id} -- No comments found")
                    } else {
                        val commentThreads = recentMessages.groupBy { it.annotationId }
                        val commentContext = commentThreads.mapValues { (_, comments) ->
                            val allSectionIds = comments.map { comment ->
                                try {
                                    comment.highlightSectionIds
                                } catch (e: Exception) {
                                    null
                                }.orEmpty().toSet()
                            }
                            require(allSectionIds.distinct().size == 1) {
                                "Found multiple sections related to comments"
                            }
                            val sections = allSectionIds.first()
                            if (sections.isEmpty()) {
                                logger.warning("${thread.id} -- No sections found, probably documents chat")
                                "CITATION UNAVAILABLE"
                            } else {
                                if (sections.size > 1) {
                                    logger.warning("${thread.id} -- More than 1 section?? Using the first one")
                                    error("more than 1 section")
                                }
                                val section = sections.single() + if (sections.size == 1) "" else " \n AND ${sections.size - 1} SECTIONS"
                                page.getElementById(section)?.text() ?: "!!! This section was deleted from the document !!!"
                            }
                        }

                        val commentsDoc = buildString {
                            appendLine("# ${thread.title} — comments")
                            append("\n---\n\n")

                            commentContext.keys.joinToString("\n---\n\n") { id ->
                                threadToString(
                                    commentContext[id]!!,
                                    commentThreads[id]!!
                                )
                            }.let(::appendLine)
                        }

                        file.resolveSibling(file.nameWithoutExtension + "_comments.md").apply {
                            deleteIfExists()
                            writeText(commentsDoc)
                        }
                        logger.info("${thread.id} -- Saved ${recentMessages.size} comments among ${commentThreads.keys.size} threads")
                    }
                }

                FileVisitResult.CONTINUE
            }
        }
        logger.info("Finished downloading comments")
    }

    private fun StringBuilder.appendAsComment(text: String) {
        for (line in text.lines()) {
            appendLine("> $line")
        }
    }

    private fun threadToString(context: String, comments: List<QuipMessage>) = buildString {
        appendAsComment(context)
        appendLine()

        var firstComment = true
        for (comment in comments) {
            if (!firstComment) {
                appendLine()
            } else {
                firstComment = false
            }
            val wroteAt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(
                comment.createdUsec.atZone(ZoneOffset.UTC)
            )
            appendLine("### ${comment.authorName} wrote at $wroteAt")
            appendAsComment(comment.text)
        }
    }
}
