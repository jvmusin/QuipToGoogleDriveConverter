package io.github.jvmusin

object QuipMarkOriginalFiles {
    @JvmStatic
    fun main(args: Array<String>) {
        object : ProcessAllFiles("Marking original/shortcut files", skipShortcuts = false) {
            val visitedIds = mutableSetOf<String>()
            override fun visitFile(location: FileLocation) {
                location.updateJson { isOriginal = visitedIds.add(location.id) }
            }
        }.run()
    }
}
