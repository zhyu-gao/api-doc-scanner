package com.itangcent.easyapi.scanner.asm

import com.itangcent.easyapi.scanner.model.RawMethodInfo
import com.itangcent.easyapi.scanner.model.RawParamInfo
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class ApiMethodVisitor(
    private val methodName: String,
    private val methodDescriptor: String,
    private val methodSignature: String?,
    mv: MethodVisitor?,
    private val annotationReader: AnnotationReader
) : MethodVisitor(Opcodes.ASM9, mv) {

    private var httpMethod: String = ""
    private val paths = mutableListOf<String>()
    private var name: String = ""
    private var description: String = ""
    private var responseClass: String = ""
    private var isHidden: Boolean = false

    // 从方法描述符提取的参数类型
    private val argTypes: List<String> = try {
        org.objectweb.asm.Type.getArgumentTypes(methodDescriptor).map { it.className }
    } catch (_: Exception) {
        emptyList()
    }

    // 从方法描述符提取的返回类型
    private val returnType: String = try {
        val rt = org.objectweb.asm.Type.getReturnType(methodDescriptor)
        if (rt.className != "void" && rt.className != "java.lang.Void") rt.className else ""
    } catch (_: Exception) {
        ""
    }

    private val returnSignature: String = extractReturnSignature(methodSignature)

    // 按参数索引收集注解信息
    private val paramAnnotations = mutableMapOf<Int, MutableMap<String, String>>()

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        val annType = descriptor.toAnnotationType()

        // HTTP 映射注解
        if (annType in HTTP_METHOD_ANNOTATIONS) {
            if (httpMethod.isEmpty()) {
                httpMethod = annType.toHttpMethod()
            }
            return HttpMappingVisitor { values ->
                val raw = values["value"] ?: values["path"]
                when (raw) {
                    is List<*> -> paths.addAll(raw.map { it.toString() })
                    is String -> if (raw.isNotBlank()) paths.add(raw)
                }
            }
        }

        if (annType == "Hidden") {
            isHidden = true
        }

        // @ApiOperation — Swagger 2.x 方法注解（直接处理，不依赖 annotationReader）
        if (annType == "ApiOperation") {
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String, value: Any?) {
                    when (name) {
                        "value" -> this@ApiMethodVisitor.name = value?.toString() ?: ""
                        "notes" -> description = value?.toString() ?: ""
                        "httpMethod" -> if (httpMethod.isEmpty()) httpMethod = value?.toString()?.uppercase() ?: ""
                        "response" -> {
                            // ASM 对 Class 类型返回 Type 对象，对 String 返回 String
                            val raw = when (value) {
                                is org.objectweb.asm.Type -> value.className
                                else -> value?.toString() ?: ""
                            }
                            if (raw.isNotBlank() && raw != "java.lang.Void" && raw != "void") {
                                responseClass = raw.replace('/', '.')
                            }
                        }
                    }
                    super.visit(name, value)
                }

                override fun visitArray(name: String): AnnotationVisitor? {
                    // 处理 @ApiOperation(tags = {...})
                    return super.visitArray(name)
                }
            }
        }

        // @Operation — OpenAPI 3.x 方法注解
        if (annType == "Operation") {
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String, value: Any?) {
                    when (name) {
                        "summary" -> this@ApiMethodVisitor.name = value?.toString() ?: ""
                        "description" -> this@ApiMethodVisitor.description = value?.toString() ?: ""
                    }
                    super.visit(name, value)
                }

                override fun visitArray(name: String): AnnotationVisitor? {
                    // 处理 @Operation(parameters = {...}) 等
                    return super.visitArray(name)
                }
            }
        }

        return super.visitAnnotation(descriptor, visible)
    }

    /**
     * 处理参数级别注解 — ASM 调用此方法而非 visitAnnotation。
     */
    override fun visitParameterAnnotation(parameterIndex: Int, descriptor: String, visible: Boolean): AnnotationVisitor? {
        val annType = descriptor.toAnnotationType()

        // @ApiParam — Swagger 2.x 参数注解
        if (annType == "ApiParam") {
            val values = paramAnnotations.getOrPut(parameterIndex) { mutableMapOf() }
            values["__source__"] = "ApiParam"
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String, value: Any?) {
                    when (name) {
                        "value" -> values["description"] = value?.toString() ?: ""
                        "name" -> values["name"] = value?.toString() ?: ""
                        "required" -> values["required"] = value.toString()
                        "defaultValue" -> values["defaultValue"] = value?.toString() ?: ""
                        "example" -> values["example"] = value?.toString() ?: ""
                        "dataType" -> values["dataType"] = value?.toString() ?: ""
                        "paramType" -> values["paramType"] = value?.toString() ?: ""
                    }
                    super.visit(name, value)
                }

                override fun visitArray(name: String): AnnotationVisitor? {
                    // 处理 @ApiParam(allowableValues = {...}) 等
                    return super.visitArray(name)
                }
            }
        }

        // @Parameter — OpenAPI 3.x 参数注解
        if (annType == "Parameter") {
            val values = paramAnnotations.getOrPut(parameterIndex) { mutableMapOf() }
            values["__source__"] = "Parameter"
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String, value: Any?) {
                    when (name) {
                        "name" -> values["name"] = value?.toString() ?: ""
                        "description" -> values["description"] = value?.toString() ?: ""
                        "required" -> values["required"] = value.toString()
                        "example" -> values["example"] = value?.toString() ?: ""
                        "in" -> values["in"] = value?.toString() ?: ""
                        "hidden" -> values["hidden"] = value.toString()
                    }
                    super.visit(name, value)
                }
            }
        }

        // Spring MVC 参数绑定注解
        if (annType in SPRING_PARAM_ANNOTATIONS) {
            val values = paramAnnotations.getOrPut(parameterIndex) { mutableMapOf() }
            values["__source__"] = annType
            values["__binding__"] = annType.toParamBinding()
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String, value: Any?) {
                    when (name) {
                        "value", "name" -> {
                            // 如果 @ApiParam 已设置 name，不覆盖
                            if (!values.containsKey("name") || values["name"].isNullOrBlank()) {
                                values["name"] = value?.toString() ?: ""
                            }
                        }
                        "required" -> values["required"] = value.toString()
                        "defaultValue" -> values["defaultValue"] = value?.toString() ?: ""
                    }
                    super.visit(name, value)
                }
            }
        }

        return super.visitParameterAnnotation(parameterIndex, descriptor, visible)
    }

    fun buildMethodInfo(): RawMethodInfo? {
        if (paths.isEmpty() && httpMethod.isEmpty()) return null

        val parameters = buildParameterList()

        // 如果 @ApiOperation 没有设置 response，使用方法返回类型作为回退
        val finalResponseClass = responseClass.ifBlank {
            if (returnType.isNotBlank() && returnType != "void" && returnType != "java.lang.Void") {
                returnType
            } else ""
        }

        return RawMethodInfo(
            methodName = methodName,
            httpMethod = httpMethod,
            paths = paths.toList(),
            name = name.ifBlank { methodName },
            description = description,
            responseClass = finalResponseClass,
            responseSignature = returnSignature,
            isHidden = isHidden,
            parameters = parameters
        )
    }

    private fun buildParameterList(): List<RawParamInfo> {
        val params = mutableListOf<RawParamInfo>()

        for ((index, values) in paramAnnotations.toSortedMap()) {
            val source = values["__source__"] ?: ""
            val binding = values["__binding__"] ?: when (source) {
                "ApiParam" -> "Query"
                "Parameter" -> "Query"
                else -> ""
            }

            val paramName = values["name"]?.takeIf { it.isNotBlank() }
                ?: values["value"]?.takeIf { it.isNotBlank() }
                ?: "arg$index"

            val desc = values["description"] ?: ""
            val required = values["required"]?.toBooleanStrictOrNull() ?: false
            val defaultValue = values["defaultValue"] ?: ""
            val example = values["example"] ?: ""
            val dataType = values["dataType"] ?: argTypes.getOrNull(index)?.substringAfterLast('.') ?: ""

            params.add(
                RawParamInfo(
                    name = paramName,
                    description = desc,
                    required = required,
                    binding = binding,
                    defaultValue = defaultValue,
                    example = example,
                    dataType = dataType,
                    in_ = values["in"] ?: ""
                )
            )
        }

        return params
    }

    private fun String.toAnnotationType(): String =
        this.removePrefix("L").removeSuffix(";").split('/').last()

    companion object {
        val HTTP_METHOD_ANNOTATIONS = setOf(
            "RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "DeleteMapping", "PatchMapping", "Path"
        )

        val SPRING_PARAM_ANNOTATIONS = setOf(
            "RequestParam", "PathVariable", "RequestBody",
            "RequestHeader", "CookieValue"
        )

        private fun String.toHttpMethod(): String = when (this) {
            "GetMapping" -> "GET"
            "PostMapping" -> "POST"
            "PutMapping" -> "PUT"
            "DeleteMapping" -> "DELETE"
            "PatchMapping" -> "PATCH"
            else -> ""
        }

        private fun String.toParamBinding(): String = when (this) {
            "RequestParam" -> "Query"
            "PathVariable" -> "Path"
            "RequestBody" -> "Body"
            "RequestHeader" -> "Header"
            "CookieValue" -> "Cookie"
            else -> ""
        }

        private fun extractReturnSignature(signature: String?): String {
            if (signature.isNullOrBlank()) return ""
            val endParams = signature.indexOf(')')
            if (endParams < 0 || endParams == signature.lastIndex) return ""
            return signature.substring(endParams + 1)
        }
    }
}

/**
 * HTTP 映射注解访问器 — 正确处理 String[] 类型的 value/path 属性。
 */
class HttpMappingVisitor(
    private val onValues: (Map<String, Any>) -> Unit
) : AnnotationVisitor(Opcodes.ASM9) {

    private val values = mutableMapOf<String, Any>()
    private var currentArrayName: String? = null
    private val currentArrayValues = mutableListOf<Any>()

    override fun visit(name: String, value: Any?) {
        value?.let { values[name] = it }
        super.visit(name, value)
    }

    override fun visitArray(name: String): AnnotationVisitor? {
        currentArrayName = name
        currentArrayValues.clear()

        return object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(name: String?, value: Any?) {
                value?.let { currentArrayValues.add(it) }
                super.visit(name, value)
            }

            override fun visitEnd() {
                currentArrayName?.let { arrayName ->
                    values[arrayName] = currentArrayValues.toList()
                }
                currentArrayName = null
                currentArrayValues.clear()
                super.visitEnd()
            }
        }
    }

    override fun visitEnd() {
        onValues(values)
        super.visitEnd()
    }
}
