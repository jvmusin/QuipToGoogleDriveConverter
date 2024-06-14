package io.github.jvmusin

data class Settings(val driveId: String, val quipFolderId: String, val driveDomain: String) {
    companion object {
        fun read(): Settings {
            val stream = Settings::class.java.getResourceAsStream("/settings.jsonc")
            requireNotNull(stream) {
                "Settings file not found (settings.jsonc)"
            }
            val settingsText = stream.use { it.readAllBytes().decodeToString() }
            return gson().fromJson(settingsText, Settings::class.java)
        }
    }
}
