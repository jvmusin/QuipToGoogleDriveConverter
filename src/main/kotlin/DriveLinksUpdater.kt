package io.github.jvmusin

import java.io.ByteArrayOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

object DriveLinksUpdater {
    private val logger = getLogger()
    private const val DRIVE_DOMAIN = "docs.google.com"

    private fun rebuildDocument(file: Path, linkIdToDriveId: Map<String, String>): Pair<Path, Map<String, String>>? {
        if (file.extension != "docx" && file.extension != "xlsx") return null

        val os = ByteArrayOutputStream()
        val updates = mutableMapOf<String, String>()
        file.inputStream().use { fileIS ->
            ZipInputStream(fileIS).use { fileZIS ->
                ZipOutputStream(os).use { outFileZOS ->
                    while (true) {
                        val e = fileZIS.nextEntry ?: break
                        val bytes = fileZIS.readAllBytes()
                        if (!e.name.endsWith(".xml.rels")) {
                            outFileZOS.putNextEntry(e)
                            outFileZOS.write(bytes)
                            continue
                        }
                        val content = bytes.decodeToString()
                        val (updated, replacements) = replaceLinks(content, linkIdToDriveId)
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

    @JvmStatic
    fun main(args: Array<String>) {
        val fileJsons = getFileJsons()
        val linkIdToDriveId = fileJsons.entries.associate {
            val linkId = it.value.quip.getAsJsonObject("thread")
                .getAsJsonPrimitive("link").asString.removePrefix("https://jetbrains.quip.com/")
            val driveId = it.value.driveInfo!!.id
            linkId to driveId
        }

        val driveClient = DriveClientFactory.createClient()
        val totalCount = fileJsons.size
        for ((i, entry) in fileJsons.entries.withIndex()) {
            val (jsonPath, fileJson) = entry
            val filePath = jsonPath.resolveSibling(fileJson.fileName)
            val prefix = "${i + 1}/$totalCount $filePath"
            val updatedFileEntry = rebuildDocument(filePath, linkIdToDriveId)
            if (updatedFileEntry == null) {
                logger.info("$prefix -- No links found, skipping")
                continue
            }
            updatedFileEntry.second.forEach { from, to ->
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
        linkIdToDriveId: Map<String, String>
    ): Pair<String, Map<String, String>> {
        val linkPattern = Regex("jetbrains.quip.com/[a-zA-Z0-9]+")
        var result = fileContent
        val replacements = mutableMapOf<String, String>()
        for (match in linkPattern.findAll(fileContent).map { it.value }.distinct()) {
            val linkId = match.substringAfter('/')
            val driveId = linkIdToDriveId[linkId] ?: continue
            val replacement = "$DRIVE_DOMAIN/document/d/$driveId"
            result = result.replace(match, replacement)
            replacements[match] = replacement
        }
        return result to replacements
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