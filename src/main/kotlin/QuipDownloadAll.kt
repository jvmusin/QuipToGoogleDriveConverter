import io.github.jvmusin.QuipDownloadComments
import io.github.jvmusin.QuipDownloadFileStructure

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
