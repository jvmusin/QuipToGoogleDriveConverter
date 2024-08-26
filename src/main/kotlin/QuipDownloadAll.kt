package io.github.jvmusin

object QuipDownloadAll {
    @JvmStatic
    fun main(args: Array<String>) {
        QuipDownloadFileStructure.main(args)
        QuipDownloadFiles.main(args)
        QuipDownloadComments.main(args)
        QuipInsertComments.main(args)
        QuipInsertAuthors.main(args)
    }
}
