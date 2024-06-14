package io.github.jvmusin

object DriveListDrives {
    @JvmStatic
    fun main(args: Array<String>) {
        val drives = DriveClientFactory.createClient().listDrives()
        println("Drives count: ${drives.size}")
        println("ID -- NAME -- KIND")
        for (drive in drives) {
            println("${drive.id} -- ${drive.name} -- ${drive.kind}")
        }
    }
}
