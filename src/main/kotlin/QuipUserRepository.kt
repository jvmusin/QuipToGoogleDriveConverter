package io.github.jvmusin

import com.google.gson.JsonObject
import kenichia.quipapi.QuipUser
import org.apache.http.client.HttpResponseException
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
    ) {
        fun formattedName() = name.formatted
    }

    private val userIdToResource = getUserIdToUserInfo()
    private val quipCache = hashMapOf<String, QuipUser>()
    private fun requestUser(id: String): QuipUser? =
        quipCache.computeIfAbsent(id.lowercase()) {
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

    fun getCachedUsers(): List<QuipUser> = quipCache.values.filter { it.name != null }

    private fun getCachedUser(id: String) = userIdToResource[id.lowercase()]

    fun getUser(id: String) = getCachedUser(id) ?: run {
        val user = requestUser(id) ?: return@run null
        if (user.name != null) {
            QuipUserResource(user.id, user.emails.orEmpty().asList(), QuipUserName(user.name))
        } else {
            null // this is a deleted user
        }
    }

    fun getUserName(id: String): String? = getUser(id)?.formattedName()

    private fun getUserEmails(id: String): List<String>? = getCachedUser(id)?.emails

    /**
     * Returns the longest email for the user.
     */
    fun getUserEmail(id: String): String? = getUserEmails(id)?.maxByOrNull { it.length }

    companion object {
        private val scimPath = Paths.get("quip_users_scim.json")
        private val extraEmailsPath = Paths.get("quip_emails_extra.json")

        private fun QuipUserResource.withLowercaseId() = copy(id = id.lowercase())

        private fun loadScimUsers(): Map<String, QuipUserResource> =
            if (scimPath.exists()) {
                gson()
                    .fromJson(Paths.get("quip_users_scim.json").readText(), JsonObject::class.java)
                    .getAsJsonArray("Resources")
                    .map { gson().fromJson(it, QuipUserResource::class.java).withLowercaseId() }
                    .associateBy { it.id.lowercase() }
            } else {
                getLogger().warning("File with SCIM users not found at $scimPath")
                emptyMap()
            }

        private fun loadExtraUsers(userIdToEmail: Map<String, QuipUserResource>): Map<String, QuipUserResource> =
            if (extraEmailsPath.exists()) {
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
                getLogger().warning("File with extra users not found at $extraEmailsPath")
                emptyMap()
            }

        private fun getUserIdToUserInfo(): Map<String, QuipUserResource> {
            val userIdToEmail = loadScimUsers()
            val extraUserIdToEmail = loadExtraUsers(userIdToEmail)
            return userIdToEmail + extraUserIdToEmail
        }

        private val NullQuipUser = object : QuipUser(JsonObject()) {}
        private fun QuipUser.unwrapNull() = if (this === NullQuipUser) null else this
        val INSTANCE = QuipUserRepository()
    }
}
