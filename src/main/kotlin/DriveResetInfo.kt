package io.github.jvmusin

import com.google.gson.JsonObject
import java.nio.file.FileVisitResult
import kotlin.io.path.*

object DriveResetInfo {
    private val log = getLogger()

    @OptIn(ExperimentalPathApi::class)
    @JvmStatic
    fun main(args: Array<String>) {
        log.info("Resetting info about files on drive")
        downloadedPath.visitFileTree {
            onVisitFile { file, _ ->
                if (file.extension == "json" && file.name != "_folder.json") {
                    val text = file.readText()
                    val json = gson().fromJson(text, JsonObject::class.java)
                    val removed = json.remove("driveInfo")
                    if (removed != null) {
                        file.writeText(gson().toJson(json))
                        log.info("Removed info about drive for file $file")
                    } else {
                        log.info("No info found, skipping file $file")
                    }
                }
                FileVisitResult.CONTINUE
            }
        }
        log.info("Done resetting drive info")
    }
}
