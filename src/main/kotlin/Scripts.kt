package io.github.jvmusin

object QuipDownloadAll {
    @JvmStatic
    fun main(args: Array<String>) {
        QuipDownloadFileStructure.main(args)
        QuipMarkOriginalFiles.main(args)
        QuipDownloadFiles.main(args)
        QuipDownloadComments.main(args)
    }
}

object ProcessDocuments {
    @JvmStatic
    fun main(args: Array<String>) {
        // TODO: Add opening-closing docs to check they're ok
        // TODO: Add a step here to collect all links and report missing info
        // Run DriveGenerateIds beforehand to have more links replaced
        QuipInsertComments.main(args)
        QuipInsertAuthors.main(args)
        DriveUpdateLinks.main(args)
    }
}

object DriveUploadAll {
    @JvmStatic
    fun main(args: Array<String>) {
        DriveGenerateIds.main(args)
        DriveUploadFiles.main(args)
    }
}
