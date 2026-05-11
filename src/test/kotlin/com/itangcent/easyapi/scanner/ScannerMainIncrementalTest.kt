package com.itangcent.easyapi.scanner

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScannerMainIncrementalTest {

    @Test
    fun `includes schema closure for changed operations`() {
        val oldJson = openApiJson(summary = "old summary")
        val newJson = openApiJson(summary = "new summary")

        val delta = assertNotNull(buildDelta(oldJson, newJson))
        val schemas = delta.getAsJsonObject("components").getAsJsonObject("schemas")

        assertNotNull(delta.getAsJsonObject("paths").getAsJsonObject("/users").getAsJsonObject("get"))
        assertNotNull(schemas.getAsJsonObject("PageUser"))
        assertNotNull(schemas.getAsJsonObject("User"))
        assertNotNull(schemas.getAsJsonObject("Department"))
        assertEquals(3, schemas.size())
    }

    @Test
    fun `includes referencing operation when referenced schema changes`() {
        val oldJson = openApiJson(userNameType = "string")
        val newJson = openApiJson(userNameType = "integer")

        val delta = assertNotNull(buildDelta(oldJson, newJson))
        val operation = delta.getAsJsonObject("paths").getAsJsonObject("/users").getAsJsonObject("get")
        val schemas = delta.getAsJsonObject("components").getAsJsonObject("schemas")

        assertEquals("list users", operation.get("summary").asString)
        assertNotNull(schemas.getAsJsonObject("PageUser"))
        assertNotNull(schemas.getAsJsonObject("Department"))
        assertEquals("integer", schemas.getAsJsonObject("User")
            .getAsJsonObject("properties")
            .getAsJsonObject("name")
            .get("type")
            .asString)
        assertEquals(3, schemas.size())
    }

    @Test
    fun `ignores schema changes that are not referenced by changed operations`() {
        val oldJson = openApiJson(unusedType = "string")
        val newJson = openApiJson(unusedType = "integer")

        assertNull(buildDelta(oldJson, newJson))
    }

    @Test
    fun `keeps security schemes in incremental document`() {
        val oldJson = openApiJson(summary = "old summary")
        val newJson = openApiJson(summary = "new summary")

        val delta = assertNotNull(buildDelta(oldJson, newJson))
        val securitySchemes = delta.getAsJsonObject("components").getAsJsonObject("securitySchemes")

        assertTrue(securitySchemes.has("Authorization"))
    }

    private fun buildDelta(oldJson: String, newJson: String): JsonObject? {
        val existingFile = File.createTempFile("easy-api-scanner", ".json")
        return try {
            existingFile.writeText(oldJson)
            val deltaJson = buildIncrementalOpenApiJson(existingFile, newJson)
            deltaJson?.let { JsonParser.parseString(it).asJsonObject }
        } finally {
            existingFile.delete()
        }
    }

    private fun openApiJson(
        summary: String = "list users",
        userNameType: String = "string",
        unusedType: String = "string"
    ): String {
        return """
            {
              "openapi": "3.1.0",
              "info": {
                "title": "Test API",
                "version": "1.0.0"
              },
              "paths": {
                "/users": {
                  "get": {
                    "summary": "$summary",
                    "responses": {
                      "200": {
                        "description": "OK",
                        "content": {
                          "application/json": {
                            "schema": {
                              "${'$'}ref": "#/components/schemas/PageUser"
                            }
                          }
                        }
                      }
                    }
                  }
                }
              },
              "components": {
                "schemas": {
                  "PageUser": {
                    "type": "object",
                    "properties": {
                      "data": {
                        "${'$'}ref": "#/components/schemas/User"
                      }
                    }
                  },
                  "User": {
                    "type": "object",
                    "properties": {
                      "name": {
                        "type": "$userNameType"
                      },
                      "department": {
                        "${'$'}ref": "#/components/schemas/Department"
                      },
                      "manager": {
                        "${'$'}ref": "#/components/schemas/User"
                      }
                    }
                  },
                  "Department": {
                    "type": "object",
                    "properties": {
                      "id": {
                        "type": "string"
                      }
                    }
                  },
                  "Unused": {
                    "type": "$unusedType"
                  }
                },
                "securitySchemes": {
                  "Authorization": {
                    "type": "apiKey",
                    "name": "Authorization",
                    "in": "header"
                  }
                }
              }
            }
        """.trimIndent()
    }
}
