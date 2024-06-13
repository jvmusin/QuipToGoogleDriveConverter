package io.github.jvmusin

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kenichia.quipapi.QuipClient
import kenichia.quipapi.QuipFolder
import kenichia.quipapi.QuipThread
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.logging.Logger
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

fun Any.getLogger(): Logger {
    System.setProperty(
        "java.util.logging.SimpleFormatter.format",
        "%1\$tF %1\$tT %4\$s %2\$s %5\$s%6\$s%n"
    )
    return Logger.getLogger(javaClass.name)
}

fun gson(): Gson = GsonBuilder().setPrettyPrinting().create()

val downloadedPath: Path = Paths.get("downloaded")

fun Any.setupQuipClient(debug: Boolean = false) {
    QuipClient.enableDebug(debug)
    QuipClient.setAccessToken(javaClass.getResource("/quip_access_token.txt")!!.readText())
}

fun Path.createNewFile(content: String) = writeText(content, Charsets.UTF_8, StandardOpenOption.CREATE_NEW)
fun Path.createNewFile(content: ByteArray) = writeBytes(content, StandardOpenOption.CREATE_NEW)
fun Path.createNewFile(content: Any) = createNewFile(gson().toJson(content))

private fun Any.toJson0(): JsonObject {
    val field = javaClass.superclass.getDeclaredField("_jsonObject")
    field.trySetAccessible()
    val jsonObject = field.get(this) as JsonObject
    return jsonObject.apply { remove("html") }
}

fun QuipThread.toJson(fileName: String): FileJson = FileJson(toJson0(), fileName)
fun QuipFolder.toJson(): FolderJson = FolderJson(toJson0())

data class FileJson(
    val quip: JsonObject,
    val fileName: String,
    val driveInfo: FileDriveInfo? = null,
)

data class FolderJson(val quip: JsonObject)
data class FileDriveInfo(val id: String)
