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
        QuipInsertComments.main(args) // TODO: Process comments text, replace links to users/documents with user names
        QuipInsertAuthors.main(args)
    }
}

object DriveUploadAll {
    @JvmStatic
    fun main(args: Array<String>) {
        DriveGenerateIds.main(args)
        DriveUpdateLinks.main(args)
        DriveUploadFiles.main(args)
    }
}

object FullCycle {
    @JvmStatic
    fun main(args: Array<String>) {
        QuipDownloadAll.main(args)
        ProcessDocuments.main(args)
        DriveUploadAll.main(args)
    }
}
