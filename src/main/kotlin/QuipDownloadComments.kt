package io.github.jvmusin

import kenichia.quipapi.QuipMessage
import kenichia.quipapi.QuipThread
import org.jsoup.Jsoup
import java.time.Instant
import java.util.*

/*
If there is no `annotation` field, then it's a document comment, no threads here
If there is an annotation without highlight_section_ids, then it's a comment to a highlighted text, threads are by id
If there is an annotation with highlight_section_ids, then it's a thread, threads are by id
 */

object QuipDownloadComments {
    @JvmStatic
    fun main(args: Array<String>) {
        setupQuipClient()
        Visitor().run()
    }

    data class SingleComment(val author: String?, val time: Instant?, val text: String)
    data class CommentsThread(val section: CommentSection, val comments: List<SingleComment>)

    data class CommentSection(val htmlId: String, val text: String?) {
        companion object {
            val DOCUMENT_CHAT = CommentSection("DOCUMENT_CHAT", null)
            val DELETED_SECTION = CommentSection("DELETED_SECTION", null)
        }
    }

    private class Visitor : ProcessAllFiles("Downloading comments from Quip") {
        private fun downloadAllComments(threadId: String): List<QuipMessage> {
            val packSize = 100
            val allMessages = TreeSet<QuipMessage>(compareBy { it.id })
            while (true) {
                val newMessages = withBackoff {
                    QuipMessage.getRecentMessages(
                        threadId,
                        packSize,
                        allMessages.minOfOrNull { it.createdUsec },  // Use the same minCreatedAt
                        // to not miss messages with the same created date.
                        // Duplicates are possible,
                        // so use TreeSet
                        // to avoid them.
                        null,
                        null,
                        QuipThread.SortedBy.DESC, // for pagination
                        null
                    )
                }
                if (!allMessages.addAll(newMessages)) break
            }
            return allMessages.sortedBy { it.createdUsec }
        }

        override fun visitFile(location: FileLocation) {
            if (location.json.quipComments != null) {
                log("Skipping already downloaded comments")
                return
            }

            val thread = location.json.quipThread()
            val html = thread.html
            val page = Jsoup.parse(html)
            log("Requesting comments")
            val recentMessages = downloadAllComments(thread.id)
            require(recentMessages.size < 100) {
                "Too many comments"
            }
            if (recentMessages.isEmpty()) {
                log("No comments found")
                location.updateJson { quipComments = emptyList() }
            } else {
                log("Saving comments")
                val commentsByThreadId = recentMessages.groupBy { it.annotationId }
                val contextByThreadId = commentsByThreadId.mapValues { (_, comments) ->
                    val allSectionIds = comments.map { comment ->
                        try {
                            comment.highlightSectionIds.toSet()
                        } catch (e: NullPointerException) { // highlightSectionIds is absent, and getter throws NPE
                            // this is a comment to just some text OR a document chat if null
                            setOfNotNull(comment.annotationId)
                        }
                    }
                    require(allSectionIds.distinct().size == 1) {
                        "Found multiple sections related to comments"
                    }
                    val sections = allSectionIds.first()
                    if (sections.isEmpty()) {
                        CommentSection.DOCUMENT_CHAT
                    } else {
                        val sectionId = requireNotNull(sections.singleOrNull()) {
                            "More than 1 section found"
                        }.replace(';', '_') // for xlsx
                        val element = page.getElementById(sectionId)
                        if (element != null) CommentSection(sectionId, element.text())
                        else CommentSection.DELETED_SECTION
                    }
                }

                val comments = commentsByThreadId.keys.map { id ->
                    val comments = commentsByThreadId[id]!!
                    val context = contextByThreadId[id]!!
                    CommentsThread(context, comments.map {
                        SingleComment(it.authorName, it.createdUsec, it.text)
                    })
                }

                location.updateJson { quipComments = comments }
                log("Comments saved")
            }
        }
    }
}
