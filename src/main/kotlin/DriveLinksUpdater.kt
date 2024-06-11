package io.github.jvmusin

import java.nio.file.FileVisitResult
import java.nio.file.Path
import kotlin.io.path.*

object DriveLinksUpdater {
    private val logger = getLogger()

    @JvmStatic
    fun main(args: Array<String>) {
        val fileJsons = getFileJsons()
        val fileToLinkId = fileJsons.mapValues {
            it.value.quip.getAsJsonObject("thread")
                .getAsJsonPrimitive("link").asString.removePrefix("https://jetbrains.quip.com/")
        }
        val linkIdToDriveId = fileJsons.entries.associate {
            val link = fileToLinkId[it.key]!!
            val driveId = it.value.driveInfo!!.id
            link to driveId
        }

        for ((jsonPath, fileJson) in fileJsons) {
            val filePath = jsonPath.resolveSibling(fileJson.fileName)
            val content = filePath.readBytes()
            val linkReplacementCandidates = content.findLinkReplacementCandidates()
            if (linkReplacementCandidates.isEmpty()) {
                logger.info("$filePath -- No links found, skipping")
                continue
            }

            logger.info("$filePath -- Processing ${linkReplacementCandidates.size} candidates")
            var result = content
            var updated = false
            for (candidate in linkReplacementCandidates.asReversed()) {
                val linkId = candidate.linkId
                val loggableQuipLink = "https://jetbrains.quip.com/$linkId"
                if (linkId !in linkIdToDriveId) {
                    logger.info("$filePath -- Link $loggableQuipLink leads to something unknown, skipping")
                    continue
                }
                val driveId = linkIdToDriveId[linkId]!!
                val driveLink = "docs.google.com/document/d/$driveId"
                result = result.replace(
                    offset = candidate.offset,
                    length = LINK_PREFIX.size + LINK_ID_LENGTH,
                    driveLink.toByteArray()
                )
                val loggableDriveLink = "https://$driveLink"
                updated = true
                logger.info("$filePath -- Link $loggableQuipLink is replaced with $loggableDriveLink")
            }

            if (!updated) {
                logger.info("$filePath -- Skipping uploading, as no links were found")
            } else {
                val updatedFilePath = jsonPath.resolveSibling(fileJson.fileName.replace(".", "_updated."))
                updatedFilePath.deleteIfExists()
                updatedFilePath.createNewFile(result)
                logger.info("$filePath -- Uploading an updated file to Google Drive")
                // TODO: Uncomment to update the file
//                DriveClientFactory.createClient()
//                    .updateFile(fileJson.driveInfo!!.parent, fileJson.driveInfo.id, updatedFilePath)
            }
        }
    }

    private val LINK_PREFIX = "jetbrains.quip.com/".toByteArray()
    private const val LINK_ID_LENGTH = 12

    private fun ByteArray.replace(offset: Int, length: Int, with: ByteArray): ByteArray {
        val result = take(offset) + with.toList() + drop(offset + length)
        return result.toByteArray()
    }

    data class LinkReplacementCandidate(val offset: Int, val linkId: String)

    fun ByteArray.contains(other: ByteArray, thisOffset: Int): Boolean {
        require(thisOffset + other.size <= size)
        return other.indices.all { other[it] == this[thisOffset + it] }
    }

    private fun ByteArray.findLinkReplacementCandidates(): List<LinkReplacementCandidate> {
        val result = mutableListOf<LinkReplacementCandidate>()
        for (thisOffset in indices) {
            val prefixEnd = thisOffset + LINK_PREFIX.size
            val linkEnd = prefixEnd + LINK_ID_LENGTH
            if (linkEnd > size) break
            if (contains(LINK_PREFIX, thisOffset)) {
                val str = copyOfRange(prefixEnd, linkEnd).decodeToString()
                if (str.matches(Regex("[a-zA-Z0-9]+")))
                    result += LinkReplacementCandidate(thisOffset, str)
            }
        }
        return result
    }

    @OptIn(ExperimentalPathApi::class)
    fun getFileJsons(): Map<Path, FileJson> {
        val fileJsons = hashMapOf<Path, FileJson>()
        downloadedPath.visitFileTree {
            onVisitFile { file, attributes ->
                if (file.name != "_folder.json" && file.extension == "json") {
                    val fileJson = gson().fromJson(file.readText(), FileJson::class.java)
                    fileJsons[file] = fileJson
                }

                FileVisitResult.CONTINUE
            }
        }
        return fileJsons
    }
}