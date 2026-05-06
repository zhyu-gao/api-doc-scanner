package com.itangcent.easyapi.scanner.model

/**
 * 协议无关的 API 端点模型 — 从现有 easy-api 项目提取，去除 IntelliJ 依赖
 */
data class ApiEndpoint(
    val name: String,
    val folder: String = "",
    val description: String = "",
    val className: String = "",
    val classDescription: String = "",
    val metadata: ApiMetadata
) {
    val isHttp: Boolean get() = metadata is HttpMetadata
    val isGrpc: Boolean get() = metadata is GrpcMetadata
    val httpMetadata: HttpMetadata? get() = metadata as? HttpMetadata
    val grpcMetadata: GrpcMetadata? get() = metadata as? GrpcMetadata
    val path: String get() = when (metadata) {
        is HttpMetadata -> metadata.path
        is GrpcMetadata -> metadata.path
    }
}

/**
 * 协议特定的 API 元数据
 */
sealed interface ApiMetadata {
    val protocol: String
}

data class HttpMetadata(
    val path: String,
    val method: HttpMethod = HttpMethod.NO_METHOD,
    val parameters: List<ApiParameter> = emptyList(),
    val headers: List<ApiHeader> = emptyList(),
    val contentType: String = "",
    val body: ObjectModel? = null,
    val responseBody: ObjectModel? = null,
    val responseType: String = ""
) : ApiMetadata {
    override val protocol: String get() = "HTTP"
}

data class GrpcMetadata(
    val path: String,
    val serviceName: String = "",
    val methodName: String = "",
    val packageName: String = "",
    val streamingType: GrpcStreamingType = GrpcStreamingType.UNARY,
    val body: ObjectModel? = null,
    val responseBody: ObjectModel? = null
) : ApiMetadata {
    override val protocol: String get() = "GRPC"
}

data class ApiParameter(
    val name: String,
    val type: ParameterType = ParameterType.TEXT,
    val required: Boolean = false,
    val binding: ParameterBinding = ParameterBinding.Query,
    val defaultValue: String = "",
    val description: String = "",
    val example: String = "",
    val enumValues: List<String> = emptyList(),
    val schema: ObjectModel? = null
)

data class ApiHeader(
    val name: String,
    val value: String = "",
    val description: String = "",
    val example: String = "",
    val required: Boolean = false
)

sealed interface ParameterBinding {
    data object Query : ParameterBinding
    data object Path : ParameterBinding
    data object Header : ParameterBinding
    data object Cookie : ParameterBinding
    data object Body : ParameterBinding
    data object Form : ParameterBinding
    data object Ignored : ParameterBinding
}

enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, NO_METHOD
}

enum class ParameterType {
    TEXT, FILE
}

enum class GrpcStreamingType {
    UNARY, SERVER_STREAMING, CLIENT_STREAMING, BIDIRECTIONAL
}

/**
 * 请求/响应体对象模型
 */
sealed interface ObjectModel {
    data class Single(val type: String, val comment: String = "", val demo: Any? = null) : ObjectModel
    data class Object(
        val fields: Map<String, FieldModel> = emptyMap(),
        val id: String = ""
    ) : ObjectModel
    data class Array(val item: ObjectModel) : ObjectModel
    data class MapModel(val keyType: ObjectModel, val valueType: ObjectModel) : ObjectModel
}

data class FieldModel(
    val model: ObjectModel,
    val comment: String = "",
    val required: Boolean = false,
    val defaultValue: String = "",
    val options: List<FieldOption> = emptyList(),
    val demo: Any? = null,
    val generic: String = ""
)

data class FieldOption(
    val value: String,
    val desc: String = ""
)

fun ApiEndpoint.setParam(name: String, binding: ParameterBinding, type: String, required: Boolean, description: String, defaultValue: String = "", example: String = ""): ApiEndpoint {
    val param = ApiParameter(name, ParameterType.TEXT, required, binding, defaultValue, description, example)
    return when (val m = metadata) {
        is HttpMetadata -> copy(metadata = m.copy(parameters = m.parameters + param))
        else -> this
    }
}

fun ApiEndpoint.setHeader(name: String, value: String = "", description: String = "", required: Boolean = false): ApiEndpoint {
    val header = ApiHeader(name, value, description, "", required)
    return when (val m = metadata) {
        is HttpMetadata -> copy(metadata = m.copy(headers = m.headers + header))
        else -> this
    }
}

fun ApiEndpoint.setPathParam(name: String, type: String = "string", description: String = ""): ApiEndpoint {
    return setParam(name, ParameterBinding.Path, type, true, description)
}

fun ApiEndpoint.setBody(body: ObjectModel): ApiEndpoint {
    return when (val m = metadata) {
        is HttpMetadata -> copy(metadata = m.copy(body = body))
        else -> this
    }
}

fun ApiEndpoint.setResponseBody(body: ObjectModel): ApiEndpoint {
    return when (val m = metadata) {
        is HttpMetadata -> copy(metadata = m.copy(responseBody = body))
        else -> this
    }
}
