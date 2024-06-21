package io.github.jvmusin

import kenichia.quipapi.QuipThread
import java.io.ByteArrayOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

object DriveUpdateLinks {
    private val logger = getLogger()

    private fun rebuildDocument(
        file: Path,
        linkIdToDriveInfo: Map<String, DriveFileInfo>
    ): Pair<Path, Map<String, String>>? {
        if (file.extension != "docx" && file.extension != "xlsx") return null

        val os = ByteArrayOutputStream()
        val updates = mutableMapOf<String, String>()
        file.inputStream().use { fileIS ->
            ZipInputStream(fileIS).use { fileZIS ->
                ZipOutputStream(os).use { outFileZOS ->
                    while (true) {
                        val e = fileZIS.nextEntry ?: break
                        val bytes = fileZIS.readBytes()
                        if (!e.name.endsWith(".xml.rels")) {
                            outFileZOS.putNextEntry(e)
                            outFileZOS.write(bytes)
                            continue
                        }
                        val content = bytes.decodeToString()
                        val (updated, replacements) = replaceLinks(content, linkIdToDriveInfo)
                        if (updated != content) updates += replacements
                        outFileZOS.putNextEntry(ZipEntry(e.name))
                        outFileZOS.write(updated.encodeToByteArray())
                    }
                }
            }
        }

        if (updates.isEmpty()) return null
        val destination = file.resolveSibling(file.name.replace(".", "_updated."))
        destination.deleteIfExists()
        destination.writeBytes(os.toByteArray())
        return destination to updates
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
            val thread = it.value.quip.getAsJsonObject("thread")
            val type = thread.getAsJsonPrimitive("type").asString
            val link = thread.getAsJsonPrimitive("link").asString
            val threadType = QuipThread.Type.valueOf(type.uppercase())
            val linkId = link.removePrefix("https://jetbrains.quip.com/")
            val driveId = it.value.driveInfo!!.id
            linkId to DriveFileInfo(driveId, threadType)
        }

        val driveClient = DriveClientFactory.createClient()
        val totalCount = fileJsons.size
        for ((i, entry) in fileJsons.entries.withIndex()) {
            val (jsonPath, fileJson) = entry
            val filePath = jsonPath.resolveSibling(fileJson.fileName)
            val prefix = "${i + 1}/$totalCount $filePath"
            val updatedFileEntry = rebuildDocument(filePath, linkIdToDriveInfo)
            if (updatedFileEntry == null) {
                logger.info("$prefix -- No links found, skipping")
                continue
            }
            updatedFileEntry.second.forEach { (from, to) ->
                logger.info("$prefix -- Made replacement $from -> $to")
            }

            logger.info("$prefix -- Updating file")
            driveClient.updateFile(fileJson.driveInfo!!.id, updatedFileEntry.first)
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

    @OptIn(ExperimentalPathApi::class)
    fun getFileJsons(): Map<Path, FileJson> {
        val fileJsons = hashMapOf<Path, FileJson>()
        downloadedPath.visitFileTree {
            onVisitFile { file, _ ->
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