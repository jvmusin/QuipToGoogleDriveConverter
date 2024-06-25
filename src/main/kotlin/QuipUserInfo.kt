package io.github.jvmusin

import com.google.gson.JsonObject
import kenichia.quipapi.QuipUser

class QuipUserInfo(val id: String, val name: String, val quip: JsonObject) {
    companion object {
        fun getById(id: String): QuipUserInfo {
            val file = downloadedUsersPath.resolve("$id.json")
            return file.getOrCreate {
                val quipUser = QuipUser.getUser(id)
                QuipUserInfo(quipUser.id, quipUser.name, quipUser.getInnerJson())
            }
        }
    }

    override fun toString(): String = "$name ($id)"
}
