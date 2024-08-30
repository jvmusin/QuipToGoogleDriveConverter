package io.github.jvmusin

import io.github.jvmusin.ProcessAllFiles.Companion.runAndGet
import io.github.jvmusin.ProcessAllFiles.FileLocation
import io.github.jvmusin.ProcessAllFiles.FolderLocation
import io.github.jvmusin.ProcessAllFiles.Location.Companion.titleWithId

object DriveGenerateIds {
    @JvmStatic
    fun main(args: Array<String>) {
        val originalLocationForDuplicates = getOriginalLocationForDuplicates()
        val (fileLocations, folderLocations) = getFileAndFolderLocations(originalLocationForDuplicates)

        val driveClient = DriveClientFactory.createClient()
        val originalIds =
            driveClient.generateIds(fileLocations.size + folderLocations.size, DriveClient.IdType.FILES).iterator()
        val shortcutIds =
            driveClient.generateIds(originalLocationForDuplicates.size, DriveClient.IdType.SHORTCUTS).iterator()

        fileLocations.forEach { it.updateJson { driveFileId = originalIds.next() } }
        folderLocations.forEach { it.updateJson { driveFolderId = originalIds.next() } }
        require(!originalIds.hasNext())

        for ((duplicate, original) in originalLocationForDuplicates) {
            duplicate.updateJson {
                driveFileId = shortcutIds.next()
                originalDriveFileId = requireNotNull(original.readAgain().json.driveFileId) {
                    "Original file id was not populated for file ${duplicate.titleWithId}"
                }
            }
        }
        require(!shortcutIds.hasNext())
    }

    private fun getFileAndFolderLocations(
        originalLocationForDuplicates: Map<FileLocation, FileLocation>
    ): Pair<List<FileLocation>, List<FolderLocation>> {
        val fileLocations = mutableListOf<FileLocation>()
        val folderLocations = mutableListOf<FolderLocation>()
        object : ProcessAllFiles("Collecting original file locations") {
            override fun visitFile(location: FileLocation) {
                if (location in originalLocationForDuplicates)
                    return
                if (location.json.driveFileId == null)
                    fileLocations += location
            }

            override fun beforeVisitFolder(location: FolderLocation) {
                if (location.json.driveFolderId == null)
                    folderLocations += location
            }
        }.run()
        return fileLocations to folderLocations
    }

    private fun getOriginalLocationForDuplicates(): Map<FileLocation, FileLocation> {
        val duplicates = object : ProcessAllFiles("Locating duplicates") {
            val duplicates = mutableListOf<FileLocation>()
            override fun visitFile(location: FileLocation) {
                if (!location.isOriginal()) duplicates += location
            }
        }.runAndGet { duplicates }
        return object : ProcessAllFiles("Locating originals for duplicates") {
            val duplicateOriginal = mutableMapOf<FileLocation, FileLocation>()
            override fun visitFile(location: FileLocation) {
                if (location.isOriginal()) {
                    duplicates
                        .filter { it.id == location.id }
                        .forEach { duplicate ->
                            require(duplicate !in duplicateOriginal)
                            duplicateOriginal[duplicate] = location
                        }
                }
            }
        }.runAndGet {
            require(duplicateOriginal.keys == duplicates.toSet())
            duplicateOriginal
        }
    }
}
