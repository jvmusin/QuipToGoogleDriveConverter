package io.github.jvmusin

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kenichia.quipapi.QuipClient
import kenichia.quipapi.QuipFolder
import kenichia.quipapi.QuipThread
import kenichia.quipapi.QuipUser
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Logger
import kotlin.io.path.*

fun Any.getLogger(): Logger {
    System.setProperty(
        "java.util.logging.SimpleFormatter.format",
        "%1\$tF %1\$tT %4\$s %2\$s %5\$s%6\$s%n"
    )
    return Logger.getLogger(javaClass.name)
}

fun gson(): Gson = GsonBuilder().setPrettyPrinting().create()

private val downloadedRootPath = Paths.get("downloaded")
val downloadedPath: Path = downloadedRootPath.resolve(Settings.read().quipFolderId)
val downloadedUsersPath: Path = downloadedRootPath.resolve("_users")
val downloadedPrivateFilesPath: Path = downloadedRootPath.resolve("_private")

val unresolvedLinksPath: Path = Paths.get("unresolved_links.csv")
fun resetUnresolvedLinksFile() {
    unresolvedLinksPath.writeText("Document Name\tDocument Author\tDocument Link\tRefers to\tRefers to type\tNotes\n")
}

fun appendUnresolvedLink(documentTitle: String, author: String, linkToADocument: String, unresolvedLink: String) {
    unresolvedLinksPath.appendLines(listOf("$documentTitle\t$author\t$linkToADocument\t$unresolvedLink"))
}

fun buildDriveFileLink(driveFileId: String, threadType: QuipThread.Type) = when (threadType) {
    QuipThread.Type.DOCUMENT -> "https://docs.google.com/document/d/$driveFileId"
    QuipThread.Type.SPREADSHEET -> "https://docs.google.com/spreadsheets/d/$driveFileId"
    QuipThread.Type.SLIDES -> "https://drive.google.com/file/d/$driveFileId"
    QuipThread.Type.CHAT -> error("Chats not supported")
}

private var quipClientInitialized = false
fun Any.setupQuipClient(debug: Boolean = false) {
    if (quipClientInitialized) return
    val logger = getLogger()
    logger.info("Initializing Quip Client")
    QuipClient.enableDebug(debug)
    QuipClient.setAccessToken(Settings.readQuipAccessToken())
    logger.info("Quip Client successfully initialized")
    quipClientInitialized = true
}

fun Path.writeJson(content: Any) = writeText(gson().toJson(content), Charsets.UTF_8)

fun Path.isJson() = extension == "json"
fun Path.isFileJson() = isJson() && !isFolderJson()
fun Path.isFolderJson() = name == "_folder.json"
fun Path.readFileJson() = if (isFileJson()) gson().fromJson(readText(), FileJson::class.java) else null
fun Path.readFolderJson() = if (isFolderJson()) gson().fromJson(readText(), FolderJson::class.java) else null

private fun Any.toJson0(): JsonObject {
    val field = javaClass.superclass.getDeclaredField("_jsonObject")
    field.isAccessible = true
    return field.get(this) as JsonObject
}

fun QuipThread.getInnerJson() = toJson0()
fun QuipFolder.getInnerJson() = toJson0()
fun QuipUser.getInnerJson() = toJson0()

fun QuipThread.toJson(): FileJson = FileJson(getInnerJson())
fun QuipFolder.toJson(): FolderJson = FolderJson(getInnerJson())

fun JsonObject.toQuipThreadReflection(): QuipThread {
    val ctor = QuipThread::class.java.declaredConstructors.single()
    ctor.isAccessible = true
    return ctor.newInstance(this) as QuipThread
}

fun JsonObject.toQuipFolderReflection(): QuipFolder {
    val ctor = QuipFolder::class.java.declaredConstructors.single()
    ctor.isAccessible = true
    return ctor.newInstance(this) as QuipFolder
}

inline fun <reified T : Any> Path.getOrCreate(create: () -> T): T {
    if (!exists()) {
        createParentDirectories()
        writeJson(create())
    }
    return gson().fromJson(readText(), T::class.java)
}

data class FileJson(
    val quip: JsonObject,
    var quipComments: List<QuipDownloadComments.CommentsThread>? = null,
    var driveFileId: String? = null,
    var isOriginal: Boolean? = null,
    var originalDriveFileId: String? = null // same as driveFileId for original and another for duplicates
) {
    fun quipThread() = quip.toQuipThreadReflection()
}

data class FolderJson(val quip: JsonObject, var driveFolderId: String? = null) {
    fun quipFolder() = quip.toQuipFolderReflection()
}

data class Progress(private val s: String) {
    fun named(name: String) = Progress("$s > $name")
    fun withIndex(index: Int, total: Int) = Progress("$s (${index + 1}/$total)")
    fun action(name: String) = "$s — $name"
    fun withPrefixIndex(index: Int, total: Int) = Progress("${index + 1}/$total$s")
    override fun toString() = s
}

enum class QuipFileType(
    val quipType: QuipThread.Type,
    val extension: String,
    val download: QuipThread.() -> ByteArray
) {
    Docx(QuipThread.Type.DOCUMENT, "docx", QuipThread::exportAsDocx),
    Slides(QuipThread.Type.SLIDES, "pdf", QuipThread::exportAsPdf),
    Spreadsheet(QuipThread.Type.SPREADSHEET, "xlsx", QuipThread::exportAsXlsx);

    companion object {
        fun fromQuipThread(thread: QuipThread): QuipFileType {
            return entries.first { it.quipType == thread.type }
        }
    }
}
