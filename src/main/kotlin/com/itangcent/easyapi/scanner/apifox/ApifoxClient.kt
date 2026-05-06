package com.itangcent.easyapi.scanner.apifox

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

class ApifoxClient(
    private val token: String,
    private val projectId: String,
    private val baseUrl: String = "https://api.apifox.com",
    private val apiVersion: String = "2024-03-28"
) {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * Import OpenAPI data via URL.
     * Official doc: https://apifox-openapi.apifox.cn/api-173409873
     */
    fun importOpenApiByUrl(moduleName: String, moduleId: Long, openApiUrl: String): ApifoxImportResult {
        val url = "${baseUrl.trimEnd('/')}/v1/projects/$projectId/import-openapi?locale=zh-CN"

        val requestBody = JsonObject().apply {
            add("input", JsonObject().apply {
                addProperty("url", openApiUrl)
            })
            add("options", JsonObject().apply {
                addProperty("moduleId", moduleId)
                addProperty("endpointOverwriteBehavior", "OVERWRITE_EXISTING")
                addProperty("schemaOverwriteBehavior", "OVERWRITE_EXISTING")
                addProperty("updateFolderOfChangedEndpoint", false)
                addProperty("prependBasePath", false)
                addProperty("deleteUnmatchedResources", false)
            })
        }
        val body = gson.toJson(requestBody)
        System.err.println("Apifox import by URL for $moduleName: $openApiUrl -> module $moduleId")

        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.doOutput = true
        connection.setRequestProperty("X-Apifox-Api-Version", apiVersion)
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        val status = connection.responseCode
        val responseBody = if (status in 200..299) {
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        }
        if (status !in 200..299) {
            throw IOException("Apifox import failed for $moduleName, status=$status, response=$responseBody")
        }
        return ApifoxImportResult(moduleName, moduleId, status, responseBody, ImportCounters.parse(responseBody))
    }
}

data class ApifoxImportResult(
    val moduleName: String,
    val moduleId: Long,
    val status: Int,
    val responseBody: String,
    val counters: ImportCounters
)

data class ImportCounters(
    val endpointCreated: Int = 0,
    val endpointUpdated: Int = 0,
    val endpointFailed: Int = 0,
    val endpointIgnored: Int = 0,
    val schemaCreated: Int = 0,
    val schemaUpdated: Int = 0,
    val schemaFailed: Int = 0,
    val schemaIgnored: Int = 0
) {
    val endpointChanged: Int get() = endpointCreated + endpointUpdated

    override fun toString(): String {
        return "endpointCreated=$endpointCreated, endpointUpdated=$endpointUpdated, " +
            "endpointFailed=$endpointFailed, endpointIgnored=$endpointIgnored, " +
            "schemaCreated=$schemaCreated, schemaUpdated=$schemaUpdated, " +
            "schemaFailed=$schemaFailed, schemaIgnored=$schemaIgnored"
    }

    companion object {
        fun parse(responseBody: String): ImportCounters {
            return try {
                val counters = JsonParser.parseString(responseBody)
                    .asJsonObject
                    .getAsJsonObject("data")
                    ?.getAsJsonObject("counters")
                    ?: return ImportCounters()
                ImportCounters(
                    endpointCreated = counters.intValue("endpointCreated"),
                    endpointUpdated = counters.intValue("endpointUpdated"),
                    endpointFailed = counters.intValue("endpointFailed"),
                    endpointIgnored = counters.intValue("endpointIgnored"),
                    schemaCreated = counters.intValue("schemaCreated"),
                    schemaUpdated = counters.intValue("schemaUpdated"),
                    schemaFailed = counters.intValue("schemaFailed"),
                    schemaIgnored = counters.intValue("schemaIgnored")
                )
            } catch (_: Exception) {
                ImportCounters()
            }
        }

        private fun JsonObject.intValue(name: String): Int {
            return get(name)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        }
    }
}
