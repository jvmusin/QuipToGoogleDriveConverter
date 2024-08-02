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
import java.nio.file.StandardOpenOption
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

val downloadedPath: Path = Paths.get("downloaded")
val downloadedUsersPath: Path = Paths.get("downloaded_users")
val downloadedPrivateFilesPath: Path = Paths.get("downloaded_private_files")

fun Any.setupQuipClient(debug: Boolean = false) {
    QuipClient.enableDebug(debug)
    QuipClient.setAccessToken(javaClass.getResource("/quip_access_token.txt")!!.readText())
}

fun Path.createNewFile(content: String) = writeText(content, Charsets.UTF_8, StandardOpenOption.CREATE_NEW)
fun Path.createNewFile(content: ByteArray) = writeBytes(content, StandardOpenOption.CREATE_NEW)
fun Path.createNewFile(content: Any) = createNewFile(gson().toJson(content))

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

fun QuipThread.toJson(fileName: String): FileJson = FileJson(getInnerJson(), fileName)
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
        createNewFile(create())
    }
    return gson().fromJson(readText(), T::class.java)
}

data class FileJson(
    val quip: JsonObject,
    val fileName: String,
    val driveInfo: FileDriveInfo? = null,
) {
    fun quipThread() = quip.toQuipThreadReflection()
}

data class FolderJson(val quip: JsonObject) {
    fun quipFolder() = quip.toQuipFolderReflection()
}

data class FileDriveInfo(val id: String, val commentsId: String? = null)
