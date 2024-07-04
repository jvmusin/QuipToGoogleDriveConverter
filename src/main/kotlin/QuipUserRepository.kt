package io.github.jvmusin

import com.google.gson.JsonObject
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

class QuipUserRepository {
    data class QuipUserName(
        val formatted: String
    )

    data class QuipUserResource(
        val id: String,
        val emails: List<String>,
        val name: QuipUserName,
    )

    private val extraEmailsPath = Paths.get("quip_emails_extra.json")

    private fun getEmailByUserId(): Map<String, QuipUserResource> {
        val userIdToEmail = gson()
            .fromJson(Paths.get("quip_users_scim.json").readText(), JsonObject::class.java)
            .getAsJsonArray("Resources")
            .map { gson().fromJson(it, QuipUserResource::class.java) }
            .filter { it.emails.isNotEmpty() }
            .associateBy { it.id }
        val extraUserIdToEmail = if (extraEmailsPath.exists()) {
            gson()
                .fromJson(extraEmailsPath.readText(), JsonObject::class.java)
                .asJsonObject
                .entrySet()
                .map { it.key to it.value.asString }
                .associate { (id, email) ->
                    val name = QuipUserName("User $email ($id)")
                    id to QuipUserResource(id, listOf(email), name)
                }
        } else {
            emptyMap()
        }
        return userIdToEmail + extraUserIdToEmail
    }

    private val userIdToResource = getEmailByUserId()

    fun getUser(id: String) = userIdToResource[id]
}