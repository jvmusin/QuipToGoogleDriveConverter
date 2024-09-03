package io.github.jvmusin

import kotlin.io.path.copyTo

object QuipInsertComments {
    @JvmStatic
    fun main(args: Array<String>) {
        object : ProcessAllFiles("Inserting comments into documents", skipShortcuts = true) {
            override fun visitFile(location: FileLocation) {
                val threads = requireNotNull(location.json.quipComments)
                    .let { OOXMLUpdateLinks.updateLinks(it, replaceMailtoWithAt = true) }
                val inputPath = location.documentPath
                val outputPath = location.withCommentsDocumentPath
                val quipThread = location.json.quipThread()
                when (location.type) {
                    QuipFileType.Docx -> {
                        log("Inserting comments into docx")
                        QuipInsertCommentsDocx.insertComments(inputPath, quipThread, threads, outputPath)
                        log("Comments inserted")
                    }

                    QuipFileType.Spreadsheet -> {
                        log("Inserting comments into spreadsheet")
                        QuipInsertCommentsSpreadsheet.insertComments(inputPath, quipThread, threads, outputPath)
                        log("Comments inserted")
                    }

                    else -> {
                        log("Skipping because file type is not ${QuipFileType.Docx} or ${QuipFileType.Spreadsheet} but ${location.type}, saving same file")
                        inputPath.copyTo(outputPath, overwrite = true)
                    }
                }
            }
        }.run()
    }
}
