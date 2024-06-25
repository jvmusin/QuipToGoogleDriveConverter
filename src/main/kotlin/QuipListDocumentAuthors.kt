package io.github.jvmusin

import kenichia.quipapi.QuipUser
import java.nio.file.FileVisitResult
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.visitFileTree

object QuipListDocumentAuthors {
    @OptIn(ExperimentalPathApi::class)
    @JvmStatic
    fun main(args: Array<String>) {
        setupQuipClient()
        val ids = mutableSetOf<String>()
        downloadedPath.visitFileTree {
            onVisitFile { file, _ ->
                file.readFileJson()?.let { fileJson ->
                    ids += fileJson.quip
                        .getAsJsonObject("thread")
                        .getAsJsonPrimitive("author_id")
                        .asString
                }

                FileVisitResult.CONTINUE
            }
        }
        println("Ids count: ${ids.size}")
        val users = QuipUser.getUsers(ids.toTypedArray())
        println("Users count: ${users.size}")
        for (user in users.sortedBy { it.name }) {
            println("${user.id} ${user.name}")
        }
    }
}
