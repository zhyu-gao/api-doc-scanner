package com.itangcent.easyapi.scanner.model

/**
 * 从 ASM 注解读取器提取的原始类信息
 */
data class RawClassInfo(
    val fullyQualifiedName: String,
    val simpleName: String,
    val isController: Boolean,
    val basePath: String = "",
    val description: String = "",
    val groupName: String = "",
    val isHidden: Boolean = false,
    val methods: List<RawMethodInfo> = emptyList()
)

/**
 * 从 ASM 注解读取器提取的原始方法信息
 */
data class RawMethodInfo(
    val methodName: String,
    val httpMethod: String = "",
    val paths: List<String> = emptyList(),
    val name: String = "",
    val description: String = "",
    val responseClass: String = "",
    val responseSignature: String = "",
    val isHidden: Boolean = false,
    val parameters: List<RawParamInfo> = emptyList()
)

/**
 * 从 ASM 注解读取器提取的原始参数信息
 */
data class RawParamInfo(
    val name: String = "",
    val description: String = "",
    val required: Boolean = false,
    val defaultValue: String = "",
    val example: String = "",
    val paramType: String = "",
    val binding: String = "",
    val dataType: String = "",
    val in_: String = ""
) {
    class Builder(
        var name: String = "",
        var description: String = "",
        var required: Boolean = false,
        var defaultValue: String = "",
        var example: String = "",
        var paramType: String = "",
        var binding: String = "",
        var dataType: String = "",
        var in_: String = ""
    ) {
        fun name(v: String) = apply { this.name = v }
        fun description(v: String) = apply { this.description = v }
        fun required(v: Boolean) = apply { this.required = v }
        fun defaultValue(v: String) = apply { this.defaultValue = v }
        fun example(v: String) = apply { this.example = v }
        fun dataType(v: String) = apply { this.dataType = v }
        fun in_(v: String) = apply { this.in_ = v }
        fun build() = RawParamInfo(name, description, required, defaultValue, example, paramType, binding, dataType, in_)
    }
}

/**
 * 请求/响应体字段模型
 */
data class RawFieldInfo(
    val name: String,
    val type: String,
    val description: String = "",
    val required: Boolean = false,
    val defaultValue: String = "",
    val example: String = "",
    val enumValues: List<String> = emptyList(),
    val nested: List<RawFieldInfo> = emptyList()
)
