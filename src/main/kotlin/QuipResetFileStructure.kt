import io.github.jvmusin.ProcessAllFiles
import kotlin.io.path.deleteExisting

object QuipResetFileStructure {
    @JvmStatic
    fun main(args: Array<String>) {
        object : ProcessAllFiles("Resetting file structure") {
            override fun afterVisitFolder(location: FolderLocation) {
                location.path.resolve("_folder.json").deleteExisting()
            }
        }.run()
    }
}
