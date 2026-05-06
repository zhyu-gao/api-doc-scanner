package com.itangcent.easyapi.scanner.builder

import com.itangcent.easyapi.scanner.asm.ModelClassScanner
import com.itangcent.easyapi.scanner.model.*

/**
 * 将 ASM 扫描得到的 RawClassInfo 转换为 ApiEndpoint 列表
 */
object EndpointBuilder {

    private var modelScanner: ModelClassScanner? = null

    fun setModelScanner(scanner: ModelClassScanner) {
        this.modelScanner = scanner
    }

    fun build(classInfo: RawClassInfo): List<ApiEndpoint> {
        if (classInfo.isHidden) return emptyList()

        return classInfo.methods.mapNotNull { method ->
            if (method.isHidden || method.paths.isEmpty()) return@mapNotNull null

            method.paths.map { path ->
                val fullPath = buildPath(classInfo.basePath, path)
                val endpointName = method.name.ifBlank { "${method.httpMethod} $fullPath" }

                val httpMetadata = HttpMetadata(
                    path = fullPath,
                    method = resolveHttpMethod(method.httpMethod),
                    parameters = method.parameters.map { param ->
                        ApiParameter(
                            name = param.name.ifBlank { "arg" },
                            type = ParameterType.TEXT,
                            required = param.required,
                            binding = resolveBinding(param),
                            defaultValue = param.defaultValue,
                            description = param.description.ifBlank { param.name },
                            example = param.example,
                            enumValues = emptyList()
                        )
                    },
                    headers = emptyList(),
                    body = resolveRequestBody(method),
                    responseBody = resolveResponseBody(method)
                )

                ApiEndpoint(
                    name = endpointName,
                    folder = classInfo.groupName.ifBlank { classInfo.simpleName },
                    description = method.description.ifBlank { classInfo.description },
                    className = classInfo.fullyQualifiedName,
                    classDescription = classInfo.description,
                    metadata = httpMetadata
                )
            }
        }.flatten()
    }

    private fun buildPath(basePath: String, methodPath: String): String {
        val base = basePath.trimEnd('/')
        val method = methodPath.trimStart('/')
        return if (base.isEmpty()) "/$method" else "/$base/$method"
    }

    private fun resolveHttpMethod(method: String): HttpMethod {
        return when (method.uppercase()) {
            "GET" -> HttpMethod.GET
            "POST" -> HttpMethod.POST
            "PUT" -> HttpMethod.PUT
            "DELETE" -> HttpMethod.DELETE
            "PATCH" -> HttpMethod.PATCH
            "HEAD" -> HttpMethod.HEAD
            "OPTIONS" -> HttpMethod.OPTIONS
            else -> HttpMethod.NO_METHOD
        }
    }

    private fun resolveBinding(param: RawParamInfo): ParameterBinding {
        if (param.binding.isNotEmpty()) {
            return when (param.binding) {
                "Query" -> ParameterBinding.Query
                "Path" -> ParameterBinding.Path
                "Header" -> ParameterBinding.Header
                "Cookie" -> ParameterBinding.Cookie
                "Body" -> ParameterBinding.Body
                "Form" -> ParameterBinding.Form
                else -> ParameterBinding.Query
            }
        }
        if (param.in_.isNotEmpty()) {
            return when (param.in_.lowercase()) {
                "query" -> ParameterBinding.Query
                "path" -> ParameterBinding.Path
                "header" -> ParameterBinding.Header
                "cookie" -> ParameterBinding.Cookie
                "body" -> ParameterBinding.Body
                "form" -> ParameterBinding.Form
                else -> ParameterBinding.Query
            }
        }
        return ParameterBinding.Query
    }

    /**
     * 构建请求体：如果有 @RequestBody 参数，尝试解析其类型结构
     */
    private fun resolveRequestBody(method: RawMethodInfo): ObjectModel? {
        val bodyParam = method.parameters.find { it.binding == "Body" } ?: return null
        // 尝试从 @ApiParam/@Parameter 中获取类型信息
        val typeName = bodyParam.dataType.ifBlank { bodyParam.name }
        if (typeName.isBlank()) return null
        return modelScanner?.resolve(typeName)
    }

    /**
     * 构建响应体：从 @ApiOperation.response 类型解析字段结构
     */
    private fun resolveResponseBody(method: RawMethodInfo): ObjectModel? {
        if (method.responseClass.isBlank()) return null

        // 尝试用 ModelClassScanner 解析响应类的字段结构
        val resolved = modelScanner?.resolve(method.responseClass, method.responseSignature)
        if (resolved != null) return resolved

        // 回退：简单类型映射
        val simpleType = method.responseClass.substringAfterLast('.')
        return ObjectModel.Single(type = simpleType, comment = method.description)
    }
}
