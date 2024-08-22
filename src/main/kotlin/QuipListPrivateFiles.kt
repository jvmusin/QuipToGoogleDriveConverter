package io.github.jvmusin

import com.google.gson.JsonObject
import io.github.jvmusin.QuipAdminApi.listAllFiles
import kenichia.quipapi.QuipFolder
import kenichia.quipapi.QuipThread
import java.nio.file.FileVisitResult
import java.nio.file.Paths
import kotlin.io.path.*
import kotlin.random.Random

object QuipListPrivateFiles {
    private val logger = getLogger()

    object FileStructure {
        data class FolderInfo(val id: String, val name: String, val parentId: String?)

        private val info = hashMapOf<String, FolderInfo>()
        private fun buildPathForFolder(folderId: String): String {
            val folderInfo = info.getOrPut(folderId) {
                QuipFolder.getFolder(folderId, false).let {
                    FolderInfo(it.id, it.title, it.parentId)
                }
            }
            return if (folderInfo.parentId == null) folderInfo.name
            else buildPathForFolder(folderInfo.parentId) + "/" + folderInfo.name
        }

        fun buildPathForThread(thread: QuipThread): String {
            val sharedFolderIds = thread.sharedFolderIds
            return when {
                sharedFolderIds == null -> "NULL"
                sharedFolderIds.isEmpty() -> "NOT_SHARED"
                sharedFolderIds.size == 1 -> buildPathForFolder(sharedFolderIds[0])
                else -> "MULTI_PARENT_" + sharedFolderIds.size + "_" + sharedFolderIds.joinToString(",")
            }
        }
    }

    data class PrivateFileInfo(
        val quip: JsonObject,
    ) {
        fun getQuipThread(): QuipThread {
            return quip.toQuipThreadReflection()
        }

        fun author(): QuipUserInfo {
            return QuipUserInfo.getById(getQuipThread().authorId)
        }

        fun path(): String {
            return FileStructure.buildPathForThread(getQuipThread())
        }

        fun ownersIds(): List<String> {
            val accessLevels = quip.getAsJsonObject("access_levels")
            val userIds = accessLevels.keySet()
            val userIdToAccessLevel = userIds.associateWith {
                val accessLevel = accessLevels.getAsJsonObject(it).getAsJsonPrimitive("access_level").asString
                AccessLevel.valueOf(accessLevel)
            }
            return userIdToAccessLevel.filterValues { it == AccessLevel.OWN }.keys.sorted()
        }

        companion object {
            fun getOrCreate(id: String): PrivateFileInfo {
                val file = downloadedPrivateFilesPath.resolve("$id.json")
                return file.getOrCreate {
                    val quipThread = QuipThread.getThread(id)
                    PrivateFileInfo(quipThread.getInnerJson())
                }
            }
        }
    }

    enum class AccessLevel {
        OWN,
        EDIT,
        COMMENT,
        VIEW,
    }

    private fun listAllNonFullySharedFiles(): Sequence<PrivateFileInfo> {
        val rootFolderMembers = QuipFolder.getFolder(Settings.read().quipFolderId, false).memberIds
        return listAllFiles()
            .map(PrivateFileInfo::getOrCreate)
            .filter { file ->
                val ownersIds = file.ownersIds()
                rootFolderMembers.any { it !in ownersIds }
            }
    }

    data class FieldRequest(val name: String, val extract: PrivateFileInfo.() -> String)

    @OptIn(ExperimentalPathApi::class)
    fun getPrivateFilesIds(): Sequence<PrivateFileInfo> {
        if (false) {
            val ids = mutableListOf<String>()
            downloadedPath.visitFileTree {
                onVisitFile { file, _ ->
                    if (file.extension == "json" && file.name != "_folder.json" && Random.nextDouble() > 0.9) {
                        ids += file.nameWithoutExtension
                    }
                    if (ids.size >= 10) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }
            }
            return ids.asSequence().map(PrivateFileInfo.Companion::getOrCreate)
        }
        return listAllNonFullySharedFiles()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        logger.info("Started listing private files")
        setupQuipClient()

        val fields = listOf(
            FieldRequest("Link") { getQuipThread().link },
            FieldRequest("Title") { getQuipThread().title },
            FieldRequest("Path") { path() },
            FieldRequest("Author") { author().toString() },
            FieldRequest("Owners") { ownersIds().map(QuipUserInfo::getById).joinToString(", ") }, // TODO: Use scim
        )

        val privateFilesLog = Paths.get("private_files.txt")
        privateFilesLog.deleteIfExists()
        privateFilesLog.createFile()
        fun printFields(block: FieldRequest.() -> String) {
            val separator = " --- "
            val line = fields.joinToString(separator) { it.block() }
            logger.info(line)
            privateFilesLog.appendLines(listOf(line))
        }

        printFields { name }
        for (fileInfo in getPrivateFilesIds()) {
            printFields { extract(fileInfo) }
        }
        logger.info("Finished listing private files")
    }
}
