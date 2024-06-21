package io.github.jvmusin

import com.google.gson.JsonObject
import org.apache.http.Consts
import org.apache.http.HttpHeaders
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.fluent.Form
import org.apache.http.client.fluent.Request

object QuipAdminApi {
    private val logger = getLogger()

    data class ListThreadsResponse(val ids: List<String>, val nextPage: Int?)

    private fun listFiles(page: Int = 0): ListThreadsResponse {
        val companyId = Settings.read().quipCompanyId
        require(companyId.isNotEmpty()) {
            "companyId not set in settings.jsonc"
        }

        val form = Form.form()
            .add("company_id", Settings.read().quipCompanyId)
            .add("page", page.toString())
            .build()

        val response = Request.Post("https://platform.quip.com/1/admin/threads/list")
            .body(UrlEncodedFormEntity(form, Consts.UTF_8))
            .setHeader(HttpHeaders.AUTHORIZATION, "Bearer ${Settings.readQuipAccessToken()}")
            .execute()
            .returnResponse()

        val body = response.entity.content.use { it.readBytes() }.decodeToString()

        if (response.statusLine.statusCode != 200) {
            throw Exception(body)
        }

        val json = gson().fromJson(body, JsonObject::class.java)
        val threadIds = json.getAsJsonArray("thread_ids").map { it.asString }
        val nextPage = json.getAsJsonPrimitive("next_page")?.asInt
        return ListThreadsResponse(threadIds, nextPage)
    }

    fun listAllFiles(): Sequence<String> = sequence {
        var page = 0
        while (true) {
            logger.info("Requesting files on page $page")
            val response = withBackoff { listFiles(page) }
            val ids = response.ids
            logger.info("Received $ids files for page $page")
            for (id in ids)
                yield(id)
            page = response.nextPage ?: break
        }
        logger.info("All files received")
    }
}
