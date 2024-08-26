package io.github.jvmusin

import jakarta.xml.bind.JAXBElement
import org.docx4j.jaxb.Context
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.openpackaging.parts.WordprocessingML.CommentsPart
import org.docx4j.wml.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.jvnet.jaxb2_commons.ppp.Child
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.*
import javax.xml.datatype.DatatypeFactory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

object QuipInsertComments {
    private val factory = Context.getWmlObjectFactory()
    private fun findFullPath(item: Child): List<Any> {
        return generateSequence<Any>(item) { last ->
            if (last is Child && last.parent is ContentAccessor) last.parent
            else null
        }.toList().reversed()
    }

    private fun findRightParentAndNode(item: Child) = findFullPath(item)
        .zipWithNext { a, b -> (a as ContentAccessor) to (b as Child) }
        .takeWhile { it.first !is R }
        .last()

    private fun addCommentStartsBefore(item: Child, commentIds: List<BigInteger>): List<CommentRangeStart> {
        val (parent, node) = findRightParentAndNode(item)
        val at = parent.content.indexOf(node)
        val starts = commentIds.map { cId ->
            factory.createCommentRangeStart().also { crStart ->
                crStart.id = cId
            }
        }
        parent.content.addAll(at, starts)
        return starts
    }

    private fun addCommentEndsAfter(item: Child, commentIds: List<BigInteger>) {
        val (parent, node) = findRightParentAndNode(item)
        val at = parent.content.indexOf(node)
        val ends = commentIds.flatMap { cId ->
            listOf(
                factory.createCommentRangeEnd().also { crEnd ->
                    crEnd.id = cId
                },
                factory.createR().also { r ->
                    r.content.add(factory.createRCommentReference().also { cr ->
                        cr.id = cId
                    })
                }
            )
        }
        parent.content.addAll(at + 1, ends)
    }

    private fun addCommentBoundsTogetherBefore(item: Child, commentIds: List<BigInteger>) {
        val starts = addCommentStartsBefore(item, commentIds)
        addCommentEndsAfter(starts.last(), commentIds)
    }

    private fun addCommentBoundsAround(firstNode: Child, lastNode: Child, commentIds: List<BigInteger>) {
        addCommentStartsBefore(firstNode, commentIds)
        addCommentEndsAfter(lastNode, commentIds)
    }

    private fun extraNode(): R = factory.createR().also { r ->
        r.content.add(factory.createText().also { t -> t.value = "\u200B"; t.space = "preserve" })
    }

    private fun createThread(
        commentsPart: Comments,
        thread: QuipDownloadComments.CommentsThread,
        startCommentId: Int
    ): List<BigInteger> {
        var commentId = startCommentId
        return thread.comments.map { comment ->
            createComment(
                commentId = commentId++.toBigInteger(),
                author = comment.author,
                date = comment.time,
                message = comment.text
            ).also(commentsPart.comment::add).id
        }
    }

    fun ProcessAllFiles.insertDocxComments(
        fileLocation: ProcessAllFiles.FileLocation
    ) {
        require(fileLocation.type == QuipFileType.Docx)
        val threads = requireNotNull(fileLocation.json.quipComments) {
            "Comments not downloaded"
        }
        val docx = WordprocessingMLPackage.load(fileLocation.documentPath.toFile())
        val commentsPart = insertCommentsPart(docx)
        var commentId = 0

        run {
            run {
                // Add document's chat
                val documentsChat = threads.filter { it.section == QuipDownloadComments.CommentSection.DOCUMENT_CHAT }
                if (documentsChat.isNotEmpty()) {
                    documentsChat.forEach { chat ->
                        val withFirstMessage = chat.copy(
                            comments = listOf(
                                QuipDownloadComments.SingleComment(
                                    author = null,
                                    time = null,
                                    text = "Document's chat"
                                )
                            ) + chat.comments
                        )
                        val commentIds =
                            createThread(commentsPart, withFirstMessage, commentId).also { commentId += it.size }
                        val titleP = findRightParentAndNode(findTextNodes(docx).first().value).first
                        addCommentBoundsAround(
                            titleP.content.first() as Child,
                            titleP.content.last() as Child,
                            commentIds
                        )
                    }
                }
            }

            run {
                // Add deleted sections
                threads.filter { it.section == QuipDownloadComments.CommentSection.DELETED_SECTION }.forEach { chat ->
                    val withFirstMessage = chat.copy(
                        comments = listOf(
                            QuipDownloadComments.SingleComment(
                                author = null,
                                time = null,
                                text = "THIS THREAD RELATES TO A SECTION WHICH WAS DELETED FROM THE DOCUMENT"
                            )
                        ) + chat.comments
                    )
                    val commentIds =
                        createThread(commentsPart, withFirstMessage, commentId).also { commentId += it.size }
                    val lastP = findParagraphs(docx).last()
                    val extraNode = extraNode()
                    lastP.content.add(extraNode)
                    addCommentBoundsAround(extraNode, extraNode, commentIds)
                }
            }
        }

        for (thread in threads) {
            if (thread.section == QuipDownloadComments.CommentSection.DOCUMENT_CHAT ||
                thread.section == QuipDownloadComments.CommentSection.DELETED_SECTION
            ) continue
            val html = fileLocation.json.quipThread().html.let(Jsoup::parse)

            fun String.noSpace() = filter { !it.isWhitespace() }

            val commentIds = createThread(commentsPart, thread, commentId).also { commentId += it.size }

            val sectionId = thread.section.htmlId
            val sectionText = thread.section.text!!

            val allTextBeforeTagHtml = findAllTextBeforeTagWithId(sectionId, html).joinToString("")
            val charactersToSkip = allTextBeforeTagHtml.noSpace().length
            val charactersToTake = sectionText.noSpace().length
            val firstNode = findFirstNode(docx, charactersToSkip)

            if (sectionText.isNotEmpty()) {
                val lastNode = findLastNode(docx, charactersToSkip + charactersToTake)
                addCommentBoundsAround(firstNode, lastNode, commentIds)
            } else {
                addCommentBoundsTogetherBefore(firstNode, commentIds)
            }

            fun validate() {
                val allTextDocx = findTextNodes(docx).joinToString("") { it.value.value }
                val commentContentDocx = cutFirstNonSpaceCharacters(allTextDocx, charactersToSkip, charactersToTake)
                require(commentContentDocx.noSpace() == sectionText.noSpace()) {
                    "Not exact match"
                }
            }
            validate()
        }
        docx.save(fileLocation.withCommentsDocumentPath.toFile())
    }

    private fun findAllTextBeforeTagWithId(id: String, html: Document): List<String> {
        var foundTag = false
        val texts = mutableListOf<String>()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node.attr("id") == id) foundTag = true
                if (foundTag) return
                if (node is TextNode) texts.add(node.text())
            }
        }, html)
        require(foundTag) { "Tag not found" }
        return texts
    }

    private fun findFirstNode(doc: WordprocessingMLPackage, skipCount: Int): Text {
        val elements = doc.mainDocumentPart.getJAXBNodesViaXPath(
            "//w:t",
            false
        ) as List<JAXBElement<Text>> // TODO: Report bug to YT, then inline findTextNodes()
        var cut = 0
        for (item in elements) {
            val nonSpaceCharacters = item.value.value.count { !it.isWhitespace() }
            if (cut + nonSpaceCharacters <= skipCount) {
                cut += nonSpaceCharacters
                continue
            }
            if (cut == skipCount) {
                // We're right at the beginning of the right tag
                // thanks to the quip's docx formatting, it should be always the case
                return item.value
            } else {
                // We should cut this tag on two tags and start the comment from the second part,
                // but thanks Quip's docx formatting, we should never have this situation
                throw NotImplementedError("Tag cutting not supported")
            }
        }
        if (cut == skipCount) {
            // comment at the end of a doc on an empty text, will still fail if no text on a page
            return elements.last().value
        }
        error("not found")
    }

    private fun findTextNodes(doc: WordprocessingMLPackage): List<JAXBElement<Text>> {
        return doc.mainDocumentPart.getJAXBNodesViaXPath("//w:t", false) as List<JAXBElement<Text>>
    }

    private fun findLastNode(doc: WordprocessingMLPackage, takeCount: Int): Text {
        val elements = findTextNodes(doc)
        var cut = 0
        for (item in elements) {
            val nonSpaceCharacters = item.value.value.count { !it.isWhitespace() }
            if (cut + nonSpaceCharacters < takeCount) {
                cut += nonSpaceCharacters
                continue
            }
            if (cut + nonSpaceCharacters == takeCount) {
                // We fully take this tag
                return item.value
            } else {
                // We should split the tag and take the first part of it to the comment,
                // but thanks Quip's docx formatting, we should never have this situation
                throw NotImplementedError("Tag cutting not supported")
            }
        }
        error("not found")
    }

    private fun findParagraphs(doc: WordprocessingMLPackage): List<P> {
        return doc.mainDocumentPart.getJAXBNodesViaXPath("//w:p", false) as List<P>
    }

    private fun cutFirstNonSpaceCharacters(text: String, skipCount: Int, takeCount: Int): String {
        val totalTake = skipCount + takeCount
        var cut = 0
        val buffer = StringBuilder()
        for (i in text.indices) {
            if (!text[i].isWhitespace()) cut++
            if (cut <= skipCount) continue
            if (takeCount > 0) buffer.append(text[i])
            else return "" // found a node
            if (cut == totalTake) return buffer.toString()
        }
        if (cut == skipCount && takeCount == 0) return "" // when skipping the whole text
        error("Not found such string")
    }

    private fun createComment(
        commentId: BigInteger,
        author: String?, date: Instant?, message: String
    ): Comments.Comment {
        val factory = Context.getWmlObjectFactory()
        return factory.createCommentsComment().also { c ->
            c.id = commentId
            c.author = author
            if (date != null) {
                c.date = DatatypeFactory.newInstance().newXMLGregorianCalendar(
                    GregorianCalendar.from(ZonedDateTime.ofInstant(date, ZoneOffset.UTC))
                )
            }
            c.content.add(factory.createP().also { p ->
                p.content.add(factory.createR().also { r ->
                    r.content.add(factory.createText().also { text ->
                        text.value = message
                    })
                })
            })
        }
    }

    private fun insertCommentsPart(doc: WordprocessingMLPackage): Comments {
        val commentsPart = CommentsPart().also(doc.mainDocumentPart::addTargetPart)
        return Comments().also(commentsPart::setJaxbElement)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        object : ProcessAllFiles("Inserting comments into documents") {
            override fun visitFile(location: FileLocation) {
                when (location.type) {
                    QuipFileType.Docx -> {
                        log("Inserting comments into docx")
                        insertDocxComments(location)
                        log("Comments inserted")
                    }

                    QuipFileType.Spreadsheet -> {
                        log("Inserting comments into spreadsheet")
                        QuipInsertCommentsSpreadsheets.insertComments(location)
                        log("Comments inserted")
                    }

                    else -> {
                        log("Skipping because file type is not ${QuipFileType.Docx} or ${QuipFileType.Spreadsheet} but ${location.type}, saving same file")
                        location.withCommentsDocumentPath.writeBytes(
                            location.documentPath.readBytes()
                        )
                    }
                }
            }
        }.run()
    }
}