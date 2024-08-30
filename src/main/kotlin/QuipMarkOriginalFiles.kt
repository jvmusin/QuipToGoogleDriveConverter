package io.github.jvmusin

object QuipMarkOriginalFiles {
    @JvmStatic
    fun main(args: Array<String>) {
        val visitedIds = mutableSetOf<String>()
        object : ProcessAllFiles() {
            override fun visitFile(location: FileLocation) {
                location.updateJson { isOriginal = visitedIds.add(location.id) }
            }
        }.run()
    }
}
