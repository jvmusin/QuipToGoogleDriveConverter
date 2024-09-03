package io.github.jvmusin

import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

object QuipInsertComments {
    @JvmStatic
    fun main(args: Array<String>) {
        object : ProcessAllFiles("Inserting comments into documents") {
            override fun visitFile(location: FileLocation) {
                when (location.type) {
                    QuipFileType.Docx -> {
                        log("Inserting comments into docx")
                        QuipInsertCommentsDocx.insertComments(location)
                        log("Comments inserted")
                    }

                    QuipFileType.Spreadsheet -> {
                        log("Inserting comments into spreadsheet")
                        QuipInsertCommentsSpreadsheet.insertComments(location)
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
