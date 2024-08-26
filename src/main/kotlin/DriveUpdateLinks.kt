package io.github.jvmusin

import io.github.jvmusin.ProcessAllFiles.FileLocation
import kenichia.quipapi.QuipThread
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

// TODO: Rewrite to use ProcessAllFiles
object DriveUpdateLinks {
    private val logger = getLogger()

    class ReplaceLinksModifier(
        private val linkIdToDriveInfo: Map<String, DriveFileInfo>
    ) {
        private val updates = mutableMapOf<String, String>()
        fun process(entry: ZipEntry, content: ByteArray): Pair<ZipEntry, ByteArray> {
            val contentString = content.decodeToString()
            require(content.contentEquals(contentString.encodeToByteArray())) {
                "Decoding+Encoding gives different result"
            }

            val (updated, replacements) = replaceLinks(contentString, linkIdToDriveInfo)
            if (updated != contentString) updates += replacements
            return ZipEntry(entry.name) to updated.encodeToByteArray()
        }

        fun updates() = updates.toMap()
    }

    private fun rebuildDocument(
        file: Path,
        linkIdToDriveInfo: Map<String, DriveFileInfo>
    ): Pair<Path, Map<String, String>>? {
        if (file.extension != "docx" && file.extension != "xlsx") return null

        val os = ByteArrayOutputStream()
        var anyLinksFound = false
        file.inputStream().use { fileIS ->
            ZipInputStream(fileIS).use { fileZIS ->
                ZipOutputStream(os).use { outFileZOS ->
                    while (true) {
                        val e = fileZIS.nextEntry ?: break
                        val bytes = fileZIS.readBytes()

                        val modifier = ReplaceLinksModifier(linkIdToDriveInfo)
                        val (newEntry, newContent) =
                            if (e.name.endsWith(".rels")) modifier.process(e, bytes)
                            else e to bytes
                        if (!bytes.contentEquals(newContent)) anyLinksFound = true
                        outFileZOS.putNextEntry(newEntry)
                        outFileZOS.write(newContent)
                    }
                }
            }
        }

        val destination = FileLocation(file.resolveSibling(file.nameWithoutExtension + ".json"))
            .withCommentsAndAuthorAndLinksDocumentPath
        destination.writeBytes(os.toByteArray())
        if (!anyLinksFound) return null
        return destination to ReplaceLinksModifier(linkIdToDriveInfo).updates()
    }

    data class DriveFileInfo(val id: String, val threadType: QuipThread.Type) {
        fun buildLink(): String {
            return when (threadType) {
                QuipThread.Type.DOCUMENT -> "docs.google.com/document/d/$id"
                QuipThread.Type.SPREADSHEET -> "docs.google.com/spreadsheets/d/$id"
                QuipThread.Type.SLIDES -> "drive.google.com/file/d/$id"
                QuipThread.Type.CHAT -> error("Chats not supported")
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val fileJsons = getFileJsons()
        val linkIdToDriveInfo = fileJsons.entries.associate {
            val thread = it.value.json.quip.getAsJsonObject("thread")
            val type = thread.getAsJsonPrimitive("type").asString
            val link = thread.getAsJsonPrimitive("link").asString
            val threadType = QuipThread.Type.valueOf(type.uppercase())
            val linkId = link.removePrefix("https://jetbrains.quip.com/")
            val driveId = it.value.json.driveFileId
            linkId to DriveFileInfo(driveId!!, threadType) // TODO: Check file existence instead of !!
        }

        val totalCount = fileJsons.size
        for ((i, entry) in fileJsons.entries.withIndex()) {
            val (_, fileJson) = entry
            val filePath = fileJson.documentPath
            val prefix = "${i + 1}/$totalCount $filePath"
            val updatedFileEntry = rebuildDocument(filePath, linkIdToDriveInfo)
            if (updatedFileEntry == null) {
                logger.info("$prefix -- No links found, skipping")
                continue
            }
            fileJsons[entry.key]!!.withCommentsAndAuthorAndLinksDocumentPath
                .writeBytes(updatedFileEntry.first.readBytes())
            updatedFileEntry.second.forEach { (from, to) ->
                logger.info("$prefix -- Made replacement $from -> $to")
            }

            val driveClient = DriveClientFactory.createClient()
            logger.info("$prefix -- Updating file")
            driveClient.updateFile(fileJson.json.driveFileId!!, updatedFileEntry.first)
            logger.info("$prefix -- File updated")
        }
    }

    fun ByteArray.contains(other: ByteArray, thisOffset: Int): Boolean {
        require(thisOffset + other.size <= size)
        return other.indices.all { other[it] == this[thisOffset + it] }
    }

    private fun replaceLinks(
        fileContent: String,
        linkIdToDriveInfo: Map<String, DriveFileInfo>
    ): Pair<String, Map<String, String>> {
        val linkRegex = Regex("([^/.]+\\.)?quip.com/[a-zA-Z0-9]+")
        var result = fileContent
        val replacements = mutableMapOf<String, String>()
        val matches = linkRegex.findAll(fileContent)
            .map { it.value }
            .distinct()
            .sortedByDescending { it.length } // first process links with a company name before "quip.com"
        for (match in matches) {
            val linkId = match.substringAfter('/')
            val driveFileInfo = linkIdToDriveInfo[linkId] ?: continue
            val replacement = driveFileInfo.buildLink()
            result = result.replace(match, replacement)
            replacements[match] = replacement
        }
        return result to replacements
    }

    private fun getFileJsons(): Map<Path, FileLocation> {
        val fileJsons = hashMapOf<Path, FileLocation>()
        val visitor = object : ProcessAllFiles("Collecting file jsons") {
            override fun visitFile(location: FileLocation) {
                fileJsons[location.path] = location
            }
        }
        visitor.run()
        return fileJsons
    }
}
