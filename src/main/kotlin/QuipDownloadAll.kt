package io.github.jvmusin

object QuipDownloadAll {
    @JvmStatic
    fun main(args: Array<String>) {
        QuipDownloadFileStructure.main(args)
        QuipDownloadFiles.main(args)
        QuipDownloadComments.main(args)
        // TODO: Add a step here to collect all links
        QuipInsertComments.main(args) // TODO: Process comments text, replace links to users/documents with user names
        QuipInsertAuthors.main(args)
        DriveGenerateIds.main(args)
        DriveUpdateLinks.main(args)
        DriveUploadFiles.main(args)
    }
}
