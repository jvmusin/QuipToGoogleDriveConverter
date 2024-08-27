package io.github.jvmusin

import com.google.gson.JsonObject
import kenichia.quipapi.QuipUser
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

    private fun QuipUserResource.withLowercaseId() = copy(id = id.lowercase())
    private val extraEmailsPath = Paths.get("quip_emails_extra.json")

    private fun getEmailByUserId(): Map<String, QuipUserResource> {
        val userIdToEmail = gson()
            .fromJson(Paths.get("quip_users_scim.json").readText(), JsonObject::class.java)
            .getAsJsonArray("Resources")
            .map { gson().fromJson(it, QuipUserResource::class.java).withLowercaseId() }
            .associateBy { it.id.lowercase() }
        val extraUserIdToEmail = if (extraEmailsPath.exists()) {
            gson()
                .fromJson(extraEmailsPath.readText(), JsonObject::class.java)
                .asJsonObject
                .entrySet()
                .associate { entry ->
                    val id = entry.key.lowercase()
                    val nameAndEmail = entry.value.asString
                    require(id !in userIdToEmail) { "Repeating id $id" }
                    val parts = nameAndEmail.split(":")
                    require(parts.size <= 2) { "Invalid name and email format for $id: $nameAndEmail" }
                    val name = parts[0]
                    val email = parts.getOrNull(1) ?: ""
                    id to QuipUserResource(id, listOfNotNull(email), QuipUserName(name))
                }
        } else {
            emptyMap()
        }
        return userIdToEmail + extraUserIdToEmail
    }

    private val userIdToResource = getEmailByUserId()
    private val quipCache = hashMapOf<String, QuipUser>()

    fun getUser(id: String) = userIdToResource[id.lowercase()]

    fun getUserName(id: String): String? {
        if (id != id.lowercase()) return getUserName(id.lowercase())
        userIdToResource[id]?.let { return it.name.formatted }
        return try {
            setupQuipClient()
            quipCache.getOrPut(id) { QuipUser.getUser(id) }.name
        } catch (e: Exception) {
            null
        }
    }

    fun getUserEmails(id: String): List<String>? {
        // QuipUser returns users without emails, so don't bother checking it in the quipCache
        return getUser(id)?.emails
    }

    /**
     * Returns the longest email for the user.
     */
    fun getUserEmail(id: String): String? {
        return getUserEmails(id)?.maxByOrNull { it.length }
    }
}