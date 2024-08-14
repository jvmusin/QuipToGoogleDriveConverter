package io.github.jvmusin

import kenichia.quipapi.QuipMessage
import kenichia.quipapi.QuipThread
import org.jsoup.Jsoup
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/*
If there is no `annotation` field, then it's a document comment, no threads here
If there is an annotation without highlight_section_ids, then it's a comment to a highlighted text, threads are by id
If there is an annotation with highlight_section_ids, then it's a thread, threads are by id
 */

object QuipDownloadComments {
    @JvmStatic
    fun main(args: Array<String>) {
        setupQuipClient()
        object : ProcessAllFiles("downloading comments from Quip") {
            override fun visitFile(location: FileLocation) {
                val thread = location.json.quipThread()
                val html = thread.html ?: withBackoff { QuipThread.getThread(thread.id).html }
                val page = Jsoup.parse(html)
                val recentMessages = withBackoff {
                    QuipMessage.getRecentMessages(
                        thread.id,
                        100,
                        null,
                        null,
                        null,
                        QuipThread.SortedBy.ASC,
                        null
                    )
                }
                if (recentMessages.isEmpty()) {
                    log("No comments found")
                } else {
                    val commentThreads = recentMessages.groupBy { it.annotationId }
                    val commentContext = commentThreads.mapValues { (_, comments) ->
                        val allSectionIds = comments.map { comment ->
                            try {
                                comment.highlightSectionIds.toSet()
                            } catch (e: NullPointerException) { // highlightSectionIds is absent and getter fails
                                setOf(comment.annotationId) // this is a comment to just some text
                            }
                        }
                        require(allSectionIds.distinct().size == 1) {
                            "Found multiple sections related to comments"
                        }
                        val sections = allSectionIds.first()
                        if (sections.isEmpty()) {
                            "Document's chat"
                        } else {
                            if (sections.size > 1) error("more than 1 section found")
                            val section =
                                sections.single() + if (sections.size == 1) "" else " \n AND ${sections.size - 1} SECTIONS"
                            page.getElementById(section)?.text()
                                ?: "!!! This section was deleted from the document !!!"
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

                    location.commentsPath.apply {
                        deleteIfExists()
                        writeText(commentsDoc)
                    }
                    log("Saved ${recentMessages.size} comments among ${commentThreads.keys.size} threads")
                }
            }
        }.run()
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
