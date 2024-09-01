package io.github.jvmusin

import com.google.gson.JsonObject
import kenichia.quipapi.QuipUser
import org.apache.http.client.HttpResponseException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

class QuipUserRepository private constructor() {
    data class QuipUserName(
        val formatted: String
    )

    data class QuipUserResource(
        val id: String,
        val emails: List<String>,
        val name: QuipUserName,
    )

    private val userIdToResource = getEmailByUserId()
    private val quipCache = hashMapOf<String, QuipUser>()
    private fun requestUser(id: String): QuipUser? {
        return quipCache.computeIfAbsent(id) {
            withBackoff {
                try {
                    setupQuipClient()
                    QuipUser.getUser(id)
                } catch (e: HttpResponseException) {
                    if (e.statusCode == 404 || e.statusCode == 400) NullQuipUser
                    else throw e
                }
            }
        }.unwrapNull()
    }

    fun getUser(id: String) = userIdToResource[id.lowercase()] ?: run {
        setupQuipClient()
        val user = requestUser(id) ?: return@run null
        if (user.name != null) {
            QuipUserResource(user.id, emptyList(), QuipUserName(user.name))
        } else {
            null // this is a deleted user
        }
    }

    fun getUserName(id: String): String? {
        if (id != id.lowercase()) return getUserName(id.lowercase())
        userIdToResource[id]?.let { return it.name.formatted }
        return requestUser(id)?.name
    }

    private fun getUserEmails(id: String): List<String>? {
        // QuipUser returns users without emails, so don't bother checking it in the quipCache
        return getUser(id)?.emails
    }

    /**
     * Returns the longest email for the user.
     */
    fun getUserEmail(id: String): String? {
        return getUserEmails(id)?.maxByOrNull { it.length }
    }

    companion object {
        private val extraEmailsPath: Path = Paths.get("quip_emails_extra.json")

        private fun QuipUserResource.withLowercaseId() = copy(id = id.lowercase())

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

        private val NullQuipUser = object : QuipUser(JsonObject()) {}
        fun QuipUser.unwrapNull() = if (this === NullQuipUser) null else this
        val INSTANCE = QuipUserRepository()
    }
}