package io.github.jvmusin

data class Settings(
    val driveId: String,
    val quipFolderId: String,
    val quipCompanyId: String,
    val driveDomain: String,
    val driveFolderName: String,
    val includeAuthorName: Boolean
) {
    companion object {
        fun read(): Settings {
            val stream = Settings::class.java.getResourceAsStream("/settings.jsonc")
            requireNotNull(stream) {
                "Settings file not found (settings.jsonc)"
            }
            val settingsText = stream.use { it.readBytes().decodeToString() }
            return gson().fromJson(settingsText, Settings::class.java)
        }

        fun readQuipAccessToken() = Settings::class.java.getResource("/quip_access_token.txt")!!.readText()
    }
}
