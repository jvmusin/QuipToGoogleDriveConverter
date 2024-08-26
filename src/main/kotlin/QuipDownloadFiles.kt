package io.github.jvmusin

import kotlin.io.path.exists
import kotlin.io.path.writeBytes

object QuipDownloadFiles {
    @JvmStatic
    fun main(args: Array<String>) {
        setupQuipClient()
        Downloader.run()
    }

    private object Downloader : ProcessAllFiles() {
        override fun visitFile(location: FileLocation) {
            val path = location.documentPath
            if (path.exists()) {
                log("Skipping already downloaded file")
                return
            }
            log("Downloading file")
            val bytes = withBackoff { location.type.download(location.json.quipThread()) }
            path.writeBytes(bytes)
            log("File downloaded")
        }
    }
}
