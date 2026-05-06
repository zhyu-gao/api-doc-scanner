package com.itangcent.easyapi.scanner.formatter

import com.itangcent.easyapi.scanner.model.*

/**
 * 独立的 Markdown 格式化器 — 将 ApiEndpoint 列表格式化为 Markdown 文档，
 * 复用现有 easy-api 项目的 DefaultMarkdownFormatter 输出风格。
 */
class StandaloneMarkdownFormatter(
    private val outputDemo: Boolean = true,
    private val maxVisits: Int = 2
) {

    fun format(endpoints: List<ApiEndpoint>, moduleName: String = "API Documentation"): String {
        val sb = StringBuilder()
        sb.append("# $moduleName\n\n")

        // 按 folder 分组
        val grouped = endpoints.groupBy { it.folder.ifBlank { "Default" } }

        var firstFolder = true
        for ((folder, folderEndpoints) in grouped) {
            if (!firstFolder) sb.append("\n")
            firstFolder = false
            sb.append("## $folder\n\n")
            sb.append("---\n\n")

            for (endpoint in folderEndpoints) {
                formatEndpoint(sb, endpoint)
            }
        }

        return sb.toString()
    }

    private fun formatEndpoint(sb: StringBuilder, endpoint: ApiEndpoint) {
        sb.append("### ${endpoint.name}\n\n")

        // Basic info
        sb.append("> BASIC\n\n")
        val httpMeta = endpoint.httpMetadata
        if (httpMeta != null) {
            sb.append("**Path:** ${endpoint.path}\n")
            sb.append("**Method:** ${httpMeta.method}\n")
        }
        if (endpoint.description.isNotBlank()) {
            sb.append("**Desc:** ${escape(endpoint.description)}\n")
        }
        sb.append("\n")

        // Request
        if (httpMeta != null) {
            sb.append("> REQUEST\n\n")

            // Path params
            val pathParams = httpMeta.parameters.filter { it.binding == ParameterBinding.Path }
            if (pathParams.isNotEmpty()) {
                sb.append("**Path Params:**\n\n")
                sb.append("| name | value | required | desc |\n")
                sb.append("|------|-------|----------|------|\n")
                for (p in pathParams) {
                    sb.append("| ${p.name} | | ${p.required} | ${escape(p.description)} |\n")
                }
                sb.append("\n")
            }

            // Query params
            val queryParams = httpMeta.parameters.filter { it.binding == ParameterBinding.Query }
            if (queryParams.isNotEmpty()) {
                sb.append("**Query:**\n\n")
                sb.append("| name | value | required | desc |\n")
                sb.append("|------|-------|----------|------|\n")
                for (p in queryParams) {
                    val defaultInfo = if (p.defaultValue.isNotBlank()) " (default: ${p.defaultValue})" else ""
                    sb.append("| ${p.name} | | ${p.required}$defaultInfo | ${escape(p.description)} |\n")
                }
                sb.append("\n")
            }

            // Headers
            val headers = httpMeta.parameters.filter { it.binding == ParameterBinding.Header }
            if (headers.isNotEmpty()) {
                sb.append("**Headers:**\n\n")
                sb.append("| name | value | required | desc |\n")
                sb.append("|------|-------|----------|------|\n")
                for (p in headers) {
                    sb.append("| ${p.name} | | ${p.required} | ${escape(p.description)} |\n")
                }
                sb.append("\n")
            }

            // Form params
            val formParams = httpMeta.parameters.filter { it.binding == ParameterBinding.Form }
            if (formParams.isNotEmpty()) {
                sb.append("**Form:**\n\n")
                sb.append("| name | value | required | type | desc |\n")
                sb.append("|------|-------|----------|------|------|\n")
                for (p in formParams) {
                    sb.append("| ${p.name} | | ${p.required} | ${p.type} | ${escape(p.description)} |\n")
                }
                sb.append("\n")
            }

            // Request body
            httpMeta.body?.let { body ->
                when (body) {
                    is ObjectModel.Single -> {
                        sb.append("**Request Type:** ${body.type}\n\n")
                    }
                    is ObjectModel.Object -> {
                        if (body.fields.isNotEmpty()) {
                            sb.append("**Request Body:**\n\n")
                            sb.append("| name | type | desc |\n")
                            sb.append("|------|------|------|\n")
                            renderObjectFields(sb, body, indent = 0)
                            sb.append("\n")
                        }
                    }
                    else -> {}
                }

                if (outputDemo) {
                    sb.append("**Request Demo:**\n\n")
                    sb.append("```json\n")
                    sb.append(generateJsonDemo(body))
                    sb.append("\n```\n\n")
                }
            }
        }

        // Response
        sb.append("> RESPONSE\n\n")
        httpMeta?.responseBody?.let { body ->
            when (body) {
                is ObjectModel.Single -> {
                    sb.append("**Response Type:** ${body.type}\n\n")
                }
                is ObjectModel.Object -> {
                    if (body.fields.isNotEmpty()) {
                        sb.append("**Body:**\n\n")
                        sb.append("| name | type | desc |\n")
                        sb.append("|------|------|------|\n")
                        renderObjectFields(sb, body, indent = 0)
                        sb.append("\n")
                    }
                }
                else -> {}
            }

            if (outputDemo) {
                sb.append("**Response Demo:**\n\n")
                sb.append("```json\n")
                sb.append(generateJsonDemo(body))
                sb.append("\n```\n\n")
            }
        }
    }

    private fun renderObjectFields(sb: StringBuilder, model: ObjectModel, indent: Int, visited: MutableSet<String> = mutableSetOf()) {
        when (model) {
            is ObjectModel.Single -> {
                // 顶层 Single 不需要渲染字段
            }
            is ObjectModel.Object -> {
                if (indent >= maxVisits) return
                for ((name, field) in model.fields) {
                    val indentStr = "&ensp;&ensp;".repeat(indent) + (if (indent > 0) "&#124;─" else "")
                    val requiredStr = if (field.required) " *(required)*" else ""
                    sb.append("| $indentStr$name | ${field.model.typeStr()}$requiredStr | ${escape(field.comment)} |\n")
                    if (field.model is ObjectModel.Object && indent < maxVisits) {
                        renderObjectFields(sb, field.model, indent + 1, visited)
                    }
                }
            }
            is ObjectModel.Array -> {
                renderObjectFields(sb, model.item, indent, visited)
            }
            is ObjectModel.MapModel -> {
                sb.append("| key (${model.keyType.typeStr()}) | ${model.valueType.typeStr()} | map |\n")
            }
        }
    }

    private fun ObjectModel.typeStr(): String = when (this) {
        is ObjectModel.Single -> type
        is ObjectModel.Object -> "object"
        is ObjectModel.Array -> "array"
        is ObjectModel.MapModel -> "map"
    }

    private fun generateJsonDemo(model: ObjectModel, visited: MutableSet<String> = mutableSetOf()): String {
        return when (model) {
            is ObjectModel.Single -> {
                model.demo?.toString() ?: when (model.type.lowercase()) {
                    "string" -> "\"string\""
                    "int", "integer", "long" -> "0"
                    "boolean" -> "false"
                    "double", "float" -> "0.0"
                    else -> "\"${model.type}\""
                }
            }
            is ObjectModel.Object -> {
                if (visited.contains(model.id)) return "\"...\""
                visited.add(model.id)
                val entries = model.fields.map { (name, field) ->
                    "  \"$name\": ${generateJsonDemo(field.model, visited)}"
                }
                "{\n${entries.joinToString(",\n")}\n}"
            }
            is ObjectModel.Array -> "[${generateJsonDemo(model.item, visited)}]"
            is ObjectModel.MapModel -> "{ \"key\": ${generateJsonDemo(model.valueType, visited)} }"
        }
    }

    private fun escape(text: String): String =
        text.replace("\n", "<br>").replace("|", "\\|")
}
