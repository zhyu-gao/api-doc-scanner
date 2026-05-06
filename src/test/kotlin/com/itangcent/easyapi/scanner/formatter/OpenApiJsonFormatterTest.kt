package com.itangcent.easyapi.scanner.formatter

import com.google.gson.JsonParser
import com.itangcent.easyapi.scanner.model.ApiEndpoint
import com.itangcent.easyapi.scanner.model.FieldModel
import com.itangcent.easyapi.scanner.model.HttpMetadata
import com.itangcent.easyapi.scanner.model.HttpMethod
import com.itangcent.easyapi.scanner.model.ObjectModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OpenApiJsonFormatterTest {

    @Test
    fun `adds authorization security scheme to components`() {
        val json = OpenApiJsonFormatter().format(emptyList())

        val root = JsonParser.parseString(json).asJsonObject
        val components = root.getAsJsonObject("components")
        val securitySchemes = components.getAsJsonObject("securitySchemes")
        val authorization = assertNotNull(securitySchemes.getAsJsonObject("Authorization"))

        assertEquals("apiKey", authorization.get("type").asString)
        assertEquals("Authorization", authorization.get("name").asString)
        assertEquals("header", authorization.get("in").asString)
        assertEquals("Bearer", authorization.get("scheme").asString)
        assertEquals("JWT", authorization.get("bearerFormat").asString)
    }

    @Test
    fun `moves object response schemas to components and references them`() {
        val responseBody = ObjectModel.Object(
            fields = linkedMapOf(
                "id" to FieldModel(ObjectModel.Single("long"), comment = "用户ID", required = true),
                "name" to FieldModel(ObjectModel.Single("string"), comment = "用户名")
            ),
            id = "com.example.UserVO"
        )
        val endpoint = ApiEndpoint(
            name = "用户详情",
            metadata = HttpMetadata(
                path = "/users/{id}",
                method = HttpMethod.GET,
                responseBody = responseBody
            )
        )

        val json = OpenApiJsonFormatter().format(listOf(endpoint))

        val root = JsonParser.parseString(json).asJsonObject
        val responseSchema = root.getAsJsonObject("paths")
            .getAsJsonObject("/users/{id}")
            .getAsJsonObject("get")
            .getAsJsonObject("responses")
            .getAsJsonObject("200")
            .getAsJsonObject("content")
            .getAsJsonObject("application/json")
            .getAsJsonObject("schema")
        val schemas = root.getAsJsonObject("components").getAsJsonObject("schemas")
        val userSchema = assertNotNull(schemas.getAsJsonObject("com.example.UserVO"))

        assertEquals("#/components/schemas/com.example.UserVO", responseSchema.get("\$ref").asString)
        assertEquals("object", userSchema.get("type").asString)
        assertEquals("integer", userSchema.getAsJsonObject("properties").getAsJsonObject("id").get("type").asString)
        assertEquals("用户名", userSchema.getAsJsonObject("properties").getAsJsonObject("name").get("description").asString)
        assertEquals("id", userSchema.getAsJsonArray("required")[0].asString)
    }
}
