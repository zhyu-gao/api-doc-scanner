package com.itangcent.easyapi.scanner.asm

import com.itangcent.easyapi.scanner.jar.ClassBytes
import com.itangcent.easyapi.scanner.model.FieldModel
import com.itangcent.easyapi.scanner.model.ObjectModel
import org.objectweb.asm.*
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor

/**
 * 扫描模型类（如 @ApiModel / @Schema 注解的类）的字段结构，
 * 用于构建请求/响应体的 ObjectModel。
 */
class ModelClassScanner(private val allClasses: Map<String, ClassBytes>) {

    private val cache = mutableMapOf<String, ObjectModel?>()

    /**
     * 根据类名（简单名或 FQN）解析为 ObjectModel。
     *
     * signature 用于保留方法返回值或字段上的泛型信息，例如：
     * Lcom/example/Resp<Lcom/example/UserVO;>;
     */
    fun resolve(typeName: String, signature: String? = null, maxDepth: Int = 3): ObjectModel? {
        if (maxDepth <= 0) return null
        val typeRef = signature
            ?.takeIf { it.isNotBlank() }
            ?.let { parseSignatureType(it) }
            ?: TypeRef.Class(typeName)
        return resolveType(typeRef, emptyMap(), maxDepth)
    }

    private fun resolveType(typeRef: TypeRef, typeVars: Map<String, TypeRef>, maxDepth: Int): ObjectModel? {
        if (maxDepth <= 0) return null
        return when (typeRef) {
            is TypeRef.Primitive -> ObjectModel.Single(type = typeRef.name)
            is TypeRef.Array -> resolveType(typeRef.item, typeVars, maxDepth - 1)?.let { ObjectModel.Array(it) }
            is TypeRef.Variable -> {
                val resolved = typeVars[typeRef.name] ?: return null
                if (resolved == typeRef) return null
                resolveType(resolved, typeVars, maxDepth - 1)
            }
            is TypeRef.Class -> resolveClassType(typeRef, typeVars, maxDepth)
        }
    }

    private fun resolveClassType(typeRef: TypeRef.Class, typeVars: Map<String, TypeRef>, maxDepth: Int): ObjectModel? {
        val className = typeRef.name
        val simpleName = className.substringAfterLast('.').substringAfterLast('/')
        primitiveTypeName(simpleName)?.let { return ObjectModel.Single(type = it) }

        val collectionItem = collectionItem(typeRef)
        if (collectionItem != null) {
            return resolveType(collectionItem, typeVars, maxDepth - 1)?.let { ObjectModel.Array(it) }
        }

        val mapTypes = mapTypes(typeRef)
        if (mapTypes != null) {
            val keyType = resolveType(mapTypes.first, typeVars, maxDepth - 1) ?: ObjectModel.Single("string")
            val valueType = resolveType(mapTypes.second, typeVars, maxDepth - 1) ?: ObjectModel.Single("object")
            return ObjectModel.MapModel(keyType, valueType)
        }

        val classBytes = allClasses[className]
            ?: allClasses.entries.firstOrNull { it.key.endsWith(".$simpleName") }?.value
            ?: return null

        val cacheKey = buildCacheKey(classBytes.fullyQualifiedName, typeRef.args)
        if (cacheKey in cache) return cache[cacheKey]

        val classInfo = readClassInfo(classBytes) ?: return null.also { cache[cacheKey] = null }
        if (classInfo.fields.isEmpty()) return null.also { cache[cacheKey] = null }

        val scopedTypeVars = typeVars + classInfo.typeParameters
            .zip(typeRef.args.map { substituteTypeVars(it, typeVars) })
            .toMap()

        val fields = mutableMapOf<String, FieldModel>()
        for (field in classInfo.fields) {
            val fieldType = field.signature
                ?.takeIf { it.isNotBlank() }
                ?.let { parseSignatureType(it) }
                ?: descriptorToTypeRef(field.descriptor)

            val model = resolveType(fieldType, scopedTypeVars, maxDepth - 1)
                ?: ObjectModel.Single(type = simplifyType(field.descriptor), comment = field.description)

            fields[field.name] = FieldModel(
                model = model,
                comment = field.description,
                required = field.required,
                defaultValue = field.defaultValue,
                demo = field.example
            )
        }

        return ObjectModel.Object(fields = fields, id = cacheKey).also {
            cache[cacheKey] = it
        }
    }

    private fun readClassInfo(classBytes: ClassBytes): ClassModelInfo? {
        val visitor = ModelClassVisitor()
        return try {
            ClassReader(classBytes.bytes).accept(visitor, ClassReader.SKIP_CODE)
            ClassModelInfo(visitor.typeParameters, visitor.fields)
        } catch (_: Exception) {
            null
        }
    }

    private fun collectionItem(typeRef: TypeRef.Class): TypeRef? {
        val name = typeRef.name.replace('/', '.')
        val isCollection = name in COLLECTION_TYPES || COLLECTION_TYPES.any { name.endsWith(".$it") }
        return if (isCollection) typeRef.args.firstOrNull() else null
    }

    private fun mapTypes(typeRef: TypeRef.Class): Pair<TypeRef, TypeRef>? {
        val name = typeRef.name.replace('/', '.')
        val isMap = name in MAP_TYPES || MAP_TYPES.any { name.endsWith(".$it") }
        if (!isMap) return null
        return Pair(typeRef.args.getOrNull(0) ?: TypeRef.Class("java.lang.String"), typeRef.args.getOrNull(1) ?: TypeRef.Class("java.lang.Object"))
    }

    private fun buildCacheKey(className: String, args: List<TypeRef>): String {
        if (args.isEmpty()) return className
        return "$className<${args.joinToString(",") { it.key() }}>"
    }

    private fun substituteTypeVars(typeRef: TypeRef, typeVars: Map<String, TypeRef>): TypeRef {
        return when (typeRef) {
            is TypeRef.Variable -> typeVars[typeRef.name]?.takeIf { it != typeRef } ?: typeRef
            is TypeRef.Array -> TypeRef.Array(substituteTypeVars(typeRef.item, typeVars))
            is TypeRef.Class -> typeRef.copy(args = typeRef.args.map { substituteTypeVars(it, typeVars) })
            is TypeRef.Primitive -> typeRef
        }
    }

    private fun parseSignatureType(signature: String): TypeRef {
        val visitor = TypeRefSignatureVisitor()
        SignatureReader(signature).acceptType(visitor)
        return visitor.typeRef ?: TypeRef.Class("java.lang.Object")
    }

    private fun descriptorToTypeRef(descriptor: String): TypeRef {
        return when {
            descriptor.startsWith("[") -> TypeRef.Array(descriptorToTypeRef(descriptor.substring(1)))
            descriptor.length == 1 -> TypeRef.Primitive(primitiveDescriptorName(descriptor))
            descriptor.startsWith("L") -> TypeRef.Class(descriptor.removePrefix("L").removeSuffix(";").replace('/', '.'))
            else -> TypeRef.Class(descriptor)
        }
    }

    private fun primitiveDescriptorName(descriptor: String): String {
        return when (descriptor) {
            "I" -> "int"
            "J" -> "long"
            "Z" -> "boolean"
            "D" -> "double"
            "F" -> "float"
            "B" -> "byte"
            "S" -> "short"
            "C" -> "char"
            "V" -> "void"
            else -> descriptor
        }
    }

    private fun primitiveTypeName(simpleName: String): String? {
        return when (simpleName) {
            "int", "Integer" -> "int"
            "long", "Long" -> "long"
            "boolean", "Boolean" -> "boolean"
            "double", "Double" -> "double"
            "float", "Float" -> "float"
            "byte", "Byte" -> "byte"
            "short", "Short" -> "short"
            "char", "Character" -> "char"
            "String" -> "string"
            "void", "Void" -> "void"
            else -> null
        }
    }

    private fun simplifyType(typeDesc: String): String {
        return when {
            typeDesc == "I" || typeDesc == "java.lang.Integer" -> "int"
            typeDesc == "J" || typeDesc == "java.lang.Long" -> "long"
            typeDesc == "Z" || typeDesc == "java.lang.Boolean" -> "boolean"
            typeDesc == "D" || typeDesc == "java.lang.Double" -> "double"
            typeDesc == "F" || typeDesc == "java.lang.Float" -> "float"
            typeDesc == "Ljava/lang/String;" || typeDesc == "java.lang.String" -> "string"
            typeDesc == "Ljava/lang/Object;" || typeDesc == "java.lang.Object" -> "object"
            typeDesc.startsWith("[") -> "array"
            typeDesc.startsWith("Ljava/") -> typeDesc.removePrefix("Ljava/").removeSuffix(";").substringAfterLast('/')
            typeDesc.startsWith("L") -> typeDesc.removePrefix("L").removeSuffix(";").substringAfterLast('/')
            typeDesc.contains('/') -> typeDesc.substringAfterLast('/')
            typeDesc.contains('.') -> typeDesc.substringAfterLast('.')
            else -> typeDesc
        }
    }

    companion object {
        private val COLLECTION_TYPES = setOf(
            "java.util.List", "java.util.Set", "java.util.Collection",
            "java.util.ArrayList", "java.util.HashSet", "java.util.LinkedList",
            "List", "Set", "Collection", "ArrayList", "HashSet", "LinkedList"
        )
        private val MAP_TYPES = setOf(
            "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
            "Map", "HashMap", "LinkedHashMap", "TreeMap"
        )
    }
}

private data class ClassModelInfo(
    val typeParameters: List<String>,
    val fields: List<FieldInfo>
)

/**
 * ASM ClassVisitor — 读取类字段上的 @ApiModelProperty / @Schema 注解
 */
private class ModelClassVisitor : ClassVisitor(Opcodes.ASM9) {

    val fields = mutableListOf<FieldInfo>()
    val typeParameters = mutableListOf<String>()

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        typeParameters.addAll(extractTypeParameters(signature))
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitField(
        access: Int, name: String, descriptor: String,
        signature: String?, value: Any?
    ): FieldVisitor? {
        if ((access and Opcodes.ACC_STATIC) != 0 || (access and Opcodes.ACC_TRANSIENT) != 0) {
            return super.visitField(access, name, descriptor, signature, value)
        }

        val fieldInfo = FieldInfo(
            name = name,
            descriptor = descriptor,
            signature = signature,
            defaultValue = value?.toString() ?: ""
        )
        fields.add(fieldInfo)

        return ModelFieldVisitor(fieldInfo, super.visitField(access, name, descriptor, signature, value))
    }

    private fun extractTypeParameters(signature: String?): List<String> {
        val params = mutableListOf<String>()
        if (signature.isNullOrBlank()) return params
        try {
            SignatureReader(signature).accept(object : SignatureVisitor(Opcodes.ASM9) {
                override fun visitFormalTypeParameter(name: String) {
                    params.add(name)
                }
            })
        } catch (_: Exception) {
            return emptyList()
        }
        return params
    }
}

private open class TypeRefSignatureVisitor(
    private val onComplete: ((TypeRef) -> Unit)? = null
) : SignatureVisitor(Opcodes.ASM9) {
    var typeRef: TypeRef? = null
        private set

    private var className: String? = null
    private val typeArguments = mutableListOf<TypeRef>()

    override fun visitBaseType(descriptor: Char) {
        complete(TypeRef.Primitive(primitiveName(descriptor)))
    }

    override fun visitArrayType(): SignatureVisitor {
        return TypeRefSignatureVisitor { complete(TypeRef.Array(it)) }
    }

    override fun visitTypeVariable(name: String) {
        complete(TypeRef.Variable(name))
    }

    override fun visitClassType(name: String) {
        className = name.replace('/', '.')
    }

    override fun visitInnerClassType(name: String) {
        className = listOfNotNull(className, name).joinToString("$")
    }

    override fun visitTypeArgument() {
        typeArguments.add(TypeRef.Class("java.lang.Object"))
    }

    override fun visitTypeArgument(wildcard: Char): SignatureVisitor {
        return TypeRefSignatureVisitor { typeArguments.add(it) }
    }

    override fun visitEnd() {
        className?.let {
            complete(TypeRef.Class(it, typeArguments.toList()))
        }
    }

    private fun complete(type: TypeRef) {
        typeRef = type
        onComplete?.invoke(type)
    }

    companion object {
        private fun primitiveName(ch: Char): String {
            return when (ch) {
                'I' -> "int"
                'J' -> "long"
                'Z' -> "boolean"
                'D' -> "double"
                'F' -> "float"
                'B' -> "byte"
                'S' -> "short"
                'C' -> "char"
                'V' -> "void"
                else -> "object"
            }
        }
    }
}

/**
 * 字段注解读取器 — 读取 @ApiModelProperty / @Schema
 */
private class ModelFieldVisitor(
    private val fieldInfo: FieldInfo,
    fv: FieldVisitor?
) : FieldVisitor(Opcodes.ASM9, fv) {

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        val annType = descriptor.removePrefix("L").removeSuffix(";").split('/').last()

        if (annType == "ApiModelProperty" || annType == "Schema") {
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String, value: Any?) {
                    when (name) {
                        "value" -> fieldInfo.description = value?.toString() ?: ""
                        "name" -> if (!value?.toString().isNullOrBlank()) fieldInfo.name = value.toString()
                        "required" -> fieldInfo.required = value as? Boolean ?: false
                        "requiredMode" -> {
                            val str = value?.toString() ?: ""
                            if (str.contains("REQUIRED")) fieldInfo.required = true
                        }
                        "example" -> fieldInfo.example = value?.toString() ?: ""
                        "defaultValue" -> fieldInfo.defaultValue = value?.toString() ?: ""
                    }
                    super.visit(name, value)
                }
            }
        }

        return super.visitAnnotation(descriptor, visible)
    }
}

private data class FieldInfo(
    var name: String,
    val descriptor: String,
    val signature: String?,
    var description: String = "",
    var required: Boolean = false,
    var defaultValue: String = "",
    var example: String = ""
)

private sealed interface TypeRef {
    data class Class(val name: String, val args: List<TypeRef> = emptyList()) : TypeRef
    data class Variable(val name: String) : TypeRef
    data class Array(val item: TypeRef) : TypeRef
    data class Primitive(val name: String) : TypeRef
}

private fun TypeRef.key(): String = when (this) {
    is TypeRef.Class -> if (args.isEmpty()) name else "$name<${args.joinToString(",") { it.key() }}>"
    is TypeRef.Variable -> "T$name"
    is TypeRef.Array -> "[${item.key()}]"
    is TypeRef.Primitive -> name
}
