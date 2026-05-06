package com.itangcent.easyapi.scanner.formatter

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.itangcent.easyapi.scanner.model.*

/**
 * 将扫描结果输出为 OpenAPI 3 JSON。
 */
class OpenApiJsonFormatter {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun format(endpoints: List<ApiEndpoint>, moduleName: String = "API Documentation"): String {
        val root = JsonObject()
        root.addProperty("openapi", "3.0.3")
        root.add("info", JsonObject().apply {
            addProperty("title", moduleName)
            addProperty("version", "1.0.0")
        })

        val paths = JsonObject()
        val schemaRegistry = SchemaRegistry()
        for (endpoint in endpoints) {
            val http = endpoint.httpMetadata ?: continue
            if (http.method == HttpMethod.NO_METHOD) continue

            val pathItem = paths.getAsJsonObject(http.path) ?: JsonObject().also {
                paths.add(http.path, it)
            }
            pathItem.add(http.method.name.lowercase(), operation(endpoint, http, schemaRegistry))
        }
        root.add("paths", paths)
        root.add("components", JsonObject().apply {
            add("schemas", schemaRegistry.schemas)
            add("securitySchemes", JsonObject().apply {
                add("Authorization", JsonObject().apply {
                    addProperty("type", "apiKey")
                    addProperty("name", "Authorization")
                    addProperty("in", "header")
                    addProperty("scheme", "Bearer")
                    addProperty("bearerFormat", "JWT")
                })
            })
        })
        return gson.toJson(root)
    }

    private fun operation(endpoint: ApiEndpoint, http: HttpMetadata, schemaRegistry: SchemaRegistry): JsonObject {
        val operation = JsonObject()
        operation.addProperty("summary", endpoint.name)
        if (endpoint.description.isNotBlank()) {
            operation.addProperty("description", endpoint.description)
        }
        if (endpoint.folder.isNotBlank()) {
            operation.add("tags", JsonArray().apply { add(endpoint.folder) })
        }

        val parameters = JsonArray()
        for (param in http.parameters) {
            val location = parameterLocation(param.binding) ?: continue
            parameters.add(JsonObject().apply {
                addProperty("name", param.name)
                addProperty("in", location)
                addProperty("required", param.required || param.binding == ParameterBinding.Path)
                if (param.description.isNotBlank()) addProperty("description", param.description)
                add(
                    "schema",
                    param.schema?.let { schemaRegistry.schemaOf(it) }
                        ?: JsonObject().apply { addProperty("type", "string") }
                )
                if (param.example.isNotBlank()) addProperty("example", param.example)
            })
        }
        if (parameters.size() > 0) operation.add("parameters", parameters)

        http.body?.let { body ->
            operation.add("requestBody", JsonObject().apply {
                addProperty("required", true)
                add("content", JsonObject().apply {
                    add("application/json", JsonObject().apply {
                        add("schema", schemaRegistry.schemaOf(body))
                    })
                })
            })
        }

        operation.add("responses", JsonObject().apply {
            add("200", JsonObject().apply {
                addProperty("description", "OK")
                http.responseBody?.let { body ->
                    add("content", JsonObject().apply {
                        add("application/json", JsonObject().apply {
                            add("schema", schemaRegistry.schemaOf(body))
                        })
                    })
                }
            })
        })

        return operation
    }

    private fun parameterLocation(binding: ParameterBinding): String? {
        return when (binding) {
            ParameterBinding.Query -> "query"
            ParameterBinding.Path -> "path"
            ParameterBinding.Header -> "header"
            ParameterBinding.Cookie -> "cookie"
            else -> null
        }
    }

    private fun primitiveSchema(type: String): JsonObject {
        val normalized = type.substringAfterLast('.').lowercase()
        return JsonObject().apply {
            when (normalized) {
                "int", "integer" -> {
                    addProperty("type", "integer")
                    addProperty("format", "int32")
                }
                "long" -> {
                    addProperty("type", "integer")
                    addProperty("format", "int64")
                }
                "float" -> {
                    addProperty("type", "number")
                    addProperty("format", "float")
                }
                "double", "bigdecimal" -> {
                    addProperty("type", "number")
                    addProperty("format", "double")
                }
                "datetime", "date", "localdate", "localdatetime", "instant" -> {
                    addProperty("type", "string")
                    addProperty("format", "date-time")
                }
                "boolean" -> addProperty("type", "boolean")
                "byte" -> {
                    addProperty("type", "string")
                    addProperty("format", "byte")
                }
                "array" -> {
                    addProperty("type", "array")
                    add("items", JsonObject().apply { addProperty("type", "object") })
                }
                "object", "void" -> addProperty("type", "object")
                else -> addProperty("type", "string")
            }
        }
    }

    private inner class SchemaRegistry {

        val schemas: JsonObject = JsonObject()
        private val schemaNames = linkedMapOf<String, String>()
        private val usedSchemaNames = mutableSetOf<String>()

        fun schemaOf(model: ObjectModel): JsonObject {
            return when (model) {
                is ObjectModel.Single -> primitiveSchema(model.type).apply {
                    if (model.comment.isNotBlank()) addProperty("description", model.comment)
                }

                is ObjectModel.Array -> JsonObject().apply {
                    addProperty("type", "array")
                    add("items", schemaOf(model.item))
                }

                is ObjectModel.MapModel -> JsonObject().apply {
                    addProperty("type", "object")
                    add("additionalProperties", schemaOf(model.valueType))
                }

                is ObjectModel.Object -> objectSchema(model)
            }
        }

        private fun objectSchema(model: ObjectModel.Object): JsonObject {
            if (model.id.isBlank()) {
                return inlineObjectSchema(model)
            }

            val name = schemaNames.getOrPut(model.id) { schemaName(model.id) }
            if (!schemas.has(name)) {
                schemas.add(name, JsonObject())
                schemas.add(name, inlineObjectSchema(model))
            }
            return refSchema(name)
        }

        private fun inlineObjectSchema(model: ObjectModel.Object): JsonObject {
            return JsonObject().apply {
                addProperty("type", "object")
                val properties = JsonObject()
                val required = JsonArray()
                for ((name, field) in model.fields) {
                    properties.add(name, fieldSchema(field))
                    if (field.required) required.add(name)
                }
                add("properties", properties)
                if (required.size() > 0) add("required", required)
            }
        }

        private fun fieldSchema(field: FieldModel): JsonObject {
            val schema = schemaOf(field.model)
            if (field.comment.isNotBlank()) addSchemaDescription(schema, field.comment)
            if (field.demo != null && field.demo.toString().isNotBlank()) {
                schema.addProperty("example", field.demo.toString())
            }
            return schema
        }

        private fun addSchemaDescription(schema: JsonObject, description: String) {
            if (schema.has("\$ref")) {
                val ref = schema.remove("\$ref")
                schema.add("allOf", JsonArray().apply {
                    add(JsonObject().apply { add("\$ref", ref) })
                })
            }
            schema.addProperty("description", description)
        }

        private fun refSchema(name: String): JsonObject {
            return JsonObject().apply {
                addProperty("\$ref", "#/components/schemas/$name")
            }
        }

        private fun schemaName(id: String): String {
            val baseName = id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "Schema" }
            var name = baseName
            var index = 2
            while (!usedSchemaNames.add(name)) {
                name = "${baseName}_$index"
                index++
            }
            return name
        }
    }
}
