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

    interface FileModifier {
        fun filter(entry: ZipEntry, documentPath: Path): Boolean

        fun process(entry: ZipEntry, content: ByteArray, documentPath: Path): Pair<ZipEntry, ByteArray>

        object Identity : FileModifier {
            override fun filter(entry: ZipEntry, documentPath: Path) = true
            override fun process(entry: ZipEntry, content: ByteArray, documentPath: Path) = entry to content
        }
    }

    class ReplaceLinksModifier(
        private val linkIdToDriveInfo: Map<String, DriveFileInfo>
    ) : FileModifier {
        private val updates = mutableMapOf<String, String>()
        override fun filter(entry: ZipEntry, documentPath: Path): Boolean = entry.name.endsWith(".xml.rels")
        override fun process(entry: ZipEntry, content: ByteArray, documentPath: Path): Pair<ZipEntry, ByteArray> {
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

    class InsertAuthorModifier : FileModifier {
        private val userRepository = QuipUserRepository()
        override fun filter(entry: ZipEntry, documentPath: Path) =
            documentPath.extension == "docx" && entry.name == "word/document.xml"

        override fun process(entry: ZipEntry, content: ByteArray, documentPath: Path): Pair<ZipEntry, ByteArray> {
            val contentString = content.decodeToString()
            require(content.contentEquals(contentString.encodeToByteArray())) {
                "Decoding+Encoding gives different result"
            }

            val fileJson = documentPath.resolveSibling(documentPath.nameWithoutExtension + ".json").readFileJson()
                ?: error("Not found file json for $documentPath")
            val authorId = fileJson.quipThread().authorId
            var authorString = ""
            val user = userRepository.getUser(authorId)
            if (user != null) {
                val email = when {
                    user.emails.isEmpty() -> ""
                    user.emails.size == 1 -> ", email ${user.emails.first()}"
                    else -> ", emails ${user.emails.joinToString(", ")}"
                }
                authorString += "Author: ${user.name.formatted}" + email
            } else {
                logger.warning("$documentPath -- User info not found for user $authorId")
                authorString += "Author: id $authorId"
            }
            val updatedContent = contentString.replaceFirst(
                "<w:body>",
                "<w:body><w:p><w:r><w:t>$authorString</w:t></w:r></w:p>"
            )
            if (contentString == updatedContent) {
                error("Not found <w:body> tag")
            }

            return ZipEntry(entry.name) to updatedContent.encodeToByteArray()
        }
    }

    private fun rebuildDocument(
        file: Path,
        linkIdToDriveInfo: Map<String, DriveFileInfo>
    ): Pair<Path, Map<String, String>>? {
        if (file.extension != "docx" && file.extension != "xlsx") return null

        val os = ByteArrayOutputStream()
        val replaceLinksModifier = ReplaceLinksModifier(linkIdToDriveInfo)
        val modifiers = buildList {
            add(replaceLinksModifier)
            if (Settings.read().includeAuthorName) add(InsertAuthorModifier())
            add(FileModifier.Identity)
        }
        file.inputStream().use { fileIS ->
            ZipInputStream(fileIS).use { fileZIS ->
                ZipOutputStream(os).use { outFileZOS ->
                    while (true) {
                        val e = fileZIS.nextEntry ?: break
                        val bytes = fileZIS.readBytes()

                        val modifier = modifiers.first { it.filter(e, file) }
                        val (newEntry, newContent) = modifier.process(e, bytes, file)
                        outFileZOS.putNextEntry(newEntry)
                        outFileZOS.write(newContent)
                    }
                }
            }
        }

        val destination = file.resolveSibling(file.name.replace(".", "_updated."))
        destination.deleteIfExists()
        destination.writeBytes(os.toByteArray())
        return destination to replaceLinksModifier.updates()
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

        if (Settings.read().includeAuthorName) {
            val userRepository = QuipUserRepository()
            for (fileJson in fileJsons.values) {
                val authorId = fileJson.quipThread().authorId
                if (userRepository.getUser(authorId) == null) {
                    error("Not found user with id $authorId")
                }
            }
        }

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

            val driveClient = DriveClientFactory.createClient()
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
