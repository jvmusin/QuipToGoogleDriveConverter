package io.github.jvmusin

import java.nio.file.Paths
import kotlin.io.path.readText

data class Settings(
    val driveId: String,
    val quipFolderId: String,
    val quipCompanyId: String,
    val driveDomain: String,
    val driveFolderName: String,
    val includeAuthorName: Boolean,
) {
    companion object {
        fun read(): Settings = gson().fromJson(Paths.get("settings.jsonc").readText(), Settings::class.java)
        fun readQuipAccessToken(): String = Paths.get("quip_access_token.txt").readText()
    }
}
