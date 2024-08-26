package io.github.jvmusin

import org.docx4j.openpackaging.packages.SpreadsheetMLPackage
import org.docx4j.openpackaging.parts.PartName
import org.docx4j.openpackaging.parts.SpreadsheetML.CommentsPart
import org.docx4j.openpackaging.parts.SpreadsheetML.WorksheetPart
import org.jsoup.Jsoup
import org.xlsx4j.sml.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object QuipInsertCommentsSpreadsheets {
    fun insertComments(location: ProcessAllFiles.FileLocation) {
        require(location.type == QuipFileType.Spreadsheet) {
            "Not a spreadsheet"
        }

        val xlsx = SpreadsheetMLPackage.load(location.documentPath.toFile())
        val sheets = xlsx.parts.parts
            .values
            .filterIsInstance<WorksheetPart>()
        val sheetComments = sheets.mapIndexed { index, worksheetPart ->
            val commentList = CTCommentList()
            CommentsPart().also { commentsPart ->
                commentsPart.setPartName(PartName("/xl/comments${index + 1}.xml"))
                worksheetPart.addTargetPart(commentsPart)
                commentsPart.contents = CTComments().also { comments ->
                    comments.commentList = commentList
                }
            }
            commentList
        }
        initImportedSheet(
            location,
            xlsx.createWorksheetPart(
                PartName("/xl/worksheets/sheet${sheets.size + 1}.xml"),
                "Imported",
                sheets.size + 1L
            )
        )

        val html = Jsoup.parse(location.json.quipThread().html)
        val tables = html.select("table")

        val comments = requireNotNull(location.json.quipComments) {
            "Comments not downloaded"
        }

        val commentsOnTables = mutableSetOf<Int>()
        for (comment in comments) {
            if (comment.section == QuipDownloadComments.CommentSection.DOCUMENT_CHAT ||
                comment.section == QuipDownloadComments.CommentSection.DELETED_SECTION
            ) {
                continue
            }
            val htmlId = comment.section.htmlId
            val cell = html.getElementById(htmlId)!!
            require(cell.tagName() == "td") { "Not td found" }
            val row = cell.parent()!!.also { p -> require(p.tagName() == "tr") }
            val columnIndex = row.children().indexOf(cell).also { require(it != -1) }
            val tbody = row.parent()!!.also { p -> require(p.tagName() == "tbody") }
            val table = tbody.parent()!!.also { p -> require(p.tagName() == "table") }
            val thead = table.child(0).also { p -> require(p.tagName() == "thead") }
            val theadRow = thead.child(0).also { p -> require(p.tagName() == "tr") }
            val columnName = theadRow.child(columnIndex).text()
            val rowName = row.child(0).text()
            val cellIndex = columnName + rowName
            val tableIndex = tables.indexOf(table).also { require(it != -1) }
            commentsOnTables += tableIndex

            sheetComments[tableIndex].comment.add(
                createComment(
                    cellIndex,
                    comment.wholeText()
                )
            )
        }

        xlsx.save(location.withCommentsDocumentPath.toFile())
    }

    private fun initImportedSheet(location: ProcessAllFiles.FileLocation, worksheetPart: WorksheetPart) {
        val worksheet = worksheetPart.contents
        worksheet.cols.add(Cols().also { c ->
            arrayOf(20, 20, 20, 80).forEachIndexed { index, w ->
                c.col.add(Col().also { col ->
                    col.isCustomWidth = true
                    col.min = index + 1L
                    col.max = index + 1L
                    col.width = w.toDouble()
                })
            }
        })
        worksheet.sheetData = SheetData().also { sd ->
            val rows = sd.row
            rows.add(Row().also { row ->
                row.r = 1
                row.c.add(newCell("A1", "Spreadsheet author"))
                val userRepository = QuipUserRepository()
                val authorId = location.json.quipThread().authorId
                val authorName = userRepository.getUser(authorId)!!.name.formatted
                row.c.add(newCell("B1", authorName))
            })
            rows.add(Row().also { row ->
                row.r = 2
                row.c.add(newCell("A2", "Created at"))
                row.c.add(newTimestampCell("B2", location.json.quipThread().createdUsec))
            })

            val comments = location.json.quipComments!!
            val chat = comments.filter { it.section == QuipDownloadComments.CommentSection.DOCUMENT_CHAT }
            require(chat.size <= 1)
            val deletedThreads = comments.filter { it.section == QuipDownloadComments.CommentSection.DELETED_SECTION }
            if (chat.size + deletedThreads.size == 0) return@also

            rows.add(Row().also { row ->
                row.r = 4
                row.c.add(newCell("A4", "Thread"))
                row.c.add(newCell("B4", "Commented at"))
                row.c.add(newCell("C4", "Comment author"))
                row.c.add(newCell("D4", "Comment text"))
            })

            var startRow = 6
            fun printThread(thread: List<QuipDownloadComments.SingleComment>, threadName: String) {
                if (startRow != 6) startRow++

                for (comment in thread) {
                    rows.add(Row().also { row ->
                        row.r = startRow++.toLong()
                        row.c.add(newCell("A${row.r}", threadName))
                        row.c.add(newTimestampCell("B${row.r}", comment.time!!))
                        row.c.add(newCell("C${row.r}", comment.author!!))
                        row.c.add(newCell("D${row.r}", comment.text))
                    })
                }
            }

            if (chat.isNotEmpty())
                printThread(chat.single().comments, "Document's chat")
            for ((index, thread) in deletedThreads.withIndex())
                printThread(thread.comments, "Deleted section #${index + 1}")
        }
    }

    private fun newCell(location: String, text: String): Cell = Cell().also { cell ->
        cell.r = location
        cell.t = STCellType.INLINE_STR
        cell.`is` = CTRst().also { ctRst ->
            ctRst.t = CTXstringWhitespace().also { ctXStringWhitespace ->
                ctXStringWhitespace.value = text
                ctXStringWhitespace.space = "preserve"
            }
        }
    }

    private fun newTimestampCell(location: String, instant: Instant): Cell = Cell().also { cell ->
        cell.r = location
        cell.f = CTCellFormula().also { f ->
            f.value = "EPOCHTODATE(${instant.epochSecond})"
        }
    }

    private fun QuipDownloadComments.CommentsThread.wholeText(): String = comments.joinToString("\n\n") { comment ->
        val timeFormatted = comment.time!!.atZone(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        "${comment.author} wrote on $timeFormatted:\n${comment.text}"
    }

    private fun createComment(cellIndex: String, text: String): CTComment = CTComment().also { comment ->
        comment.text = CTRst().also { rst ->
            rst.t = CTXstringWhitespace().also { xStringWhitespace ->
                xStringWhitespace.value = text
                xStringWhitespace.space = "preserve"
            }
        }
        comment.ref = cellIndex
    }
}
