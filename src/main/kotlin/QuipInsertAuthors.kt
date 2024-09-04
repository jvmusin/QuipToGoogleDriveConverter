package io.github.jvmusin

import org.docx4j.jaxb.Context
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

object QuipInsertAuthors {
    @JvmStatic
    fun main(args: Array<String>) {
        val factory = Context.getWmlObjectFactory()
        val userRepository = QuipUserRepository.INSTANCE

        fun getAuthorString(authorId: String): String {
            val user = requireNotNull(userRepository.getUser(authorId)) {
                "User info for user id $authorId not found"
            }
            val email = when {
                user.emails.isEmpty() -> ""
                user.emails.size == 1 -> ", email ${user.emails.single()}"
                else -> ", emails ${user.emails.joinToString(", ")}"
            }
            return "Author: ${user.formattedName()}" + email
        }

        val authorIdToDocuments = hashMapOf<String, MutableList<ProcessAllFiles.FileLocation>>()
        object : ProcessAllFiles("Collecting document authors", skipShortcuts = true) {
            override fun visitFile(location: FileLocation) {
                authorIdToDocuments.computeIfAbsent(location.json.quipThread().authorId) { mutableListOf() }
                    .add(location)
            }
        }.run()
        val absentAuthors = authorIdToDocuments.filterKeys { userRepository.getUser(it) == null }

        require(absentAuthors.isEmpty()) {
            val instruction = UnknownUsersInstructionBuilder.build(absentAuthors.keys)
            val documents = buildString {
                appendLine("Below is a list of links to files by these authors")
                for ((author, locations) in absentAuthors) {
                    val links = locations.joinToString(" ") { "https://quip.com/${it.id}" }
                    appendLine("$author: $links")
                }
            }
            "To add document authors, you need to provide names of some document authors\n$instruction\n$documents"
        }

        object : ProcessAllFiles("Saving author name in a document", skipShortcuts = true) {
            override fun visitFile(location: FileLocation) {
                if (location.type == QuipFileType.Docx) {
                    log("Writing author")
                    val doc = requireNotNull(location.withCommentsDocumentPath)
                    val docx = WordprocessingMLPackage.load(doc.toFile())
                    docx.mainDocumentPart.content.add(0, factory.createP().also { p ->
                        p.content.add(factory.createR().also { r ->
                            r.content.add(factory.createText().also { t ->
                                val authorString = getAuthorString(location.json.quipThread().authorId)
                                val createdAt = location.json.quipThread().createdUsec.atZone(ZoneOffset.UTC)
                                val createdAtFormatted = createdAt.format(DateTimeFormatter.RFC_1123_DATE_TIME)
                                t.value = "$authorString, created on $createdAtFormatted"
                            })
                        })
                    })
                    docx.save(location.withCommentsAndAuthorDocumentPath.toFile())
                    log("Done writing author")
                } else {
                    log("Author name saving only available for docx, saving file without changes")
                    location.withCommentsAndAuthorDocumentPath.writeBytes(
                        location.withCommentsDocumentPath.readBytes()
                    )
                }
            }
        }.run()
    }
}
