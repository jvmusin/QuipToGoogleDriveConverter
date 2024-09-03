package io.github.jvmusin

import io.github.jvmusin.ProcessAllFiles.FileLocation
import io.github.jvmusin.ProcessAllFiles.FolderLocation
import io.github.jvmusin.ProcessAllFiles.Location.Companion.titleWithId

object DriveGenerateIds {
    @JvmStatic
    fun main(args: Array<String>) {
        val originalLocationForDuplicates = getOriginalLocationForDuplicates()
        val (fileLocations, folderLocations) = getFileAndFolderLocations()

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

    private fun getFileAndFolderLocations(): Pair<List<FileLocation>, List<FolderLocation>> {
        val fileLocations = mutableListOf<FileLocation>()
        val folderLocations = mutableListOf<FolderLocation>()
        object : ProcessAllFiles("Collecting original file locations", skipShortcuts = true) {
            override fun visitFile(location: FileLocation) {
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
        val duplicates = mutableListOf<FileLocation>()
        object : ProcessAllFiles("Locating duplicates", skipShortcuts = false) {
            override fun visitFile(location: FileLocation) {
                if (!location.isOriginal()) duplicates += location
            }
        }.run()
        val duplicateOriginal = mutableMapOf<FileLocation, FileLocation>()
        object : ProcessAllFiles("Locating originals for duplicates", skipShortcuts = true) {
            override fun visitFile(location: FileLocation) {
                duplicates
                    .filter { it.id == location.id }
                    .forEach { duplicate ->
                        require(duplicate !in duplicateOriginal)
                        duplicateOriginal[duplicate] = location
                    }
            }
        }.run()
        require(duplicateOriginal.keys == duplicates.toSet())
        return duplicateOriginal
    }
}
