package io.github.jvmusin

import kenichia.quipapi.QuipUser

class QuipUserInfo(val id: String, val name: String) {
    companion object {
        fun getById(id: String): QuipUserInfo {
            val file = downloadedUsersPath.resolve("$id.json")
            return file.getOrCreate {
                val quipUser = QuipUser.getUser(id)
                QuipUserInfo(quipUser.id, quipUser.name)
            }
        }
    }

    override fun toString(): String = "$name ($id)"
}
