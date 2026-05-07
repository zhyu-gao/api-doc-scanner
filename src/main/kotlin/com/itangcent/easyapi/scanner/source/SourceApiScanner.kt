package com.itangcent.easyapi.scanner.source

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MemberValuePair
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr
import com.github.javaparser.ast.type.ArrayType
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.type.TypeParameter
import com.github.javaparser.ast.type.VoidType
import com.github.javaparser.ast.type.WildcardType
import com.itangcent.easyapi.scanner.model.*
import java.io.File

data class ModuleApiScanResult(
    val moduleName: String,
    val sourceRoot: File,
    val endpoints: List<ApiEndpoint>
)

class SourceApiScanner {

    fun scan(sourceRoot: File): List<ApiEndpoint> {
        return scanModules(sourceRoot).flatMap { it.endpoints }
    }

    fun scanModules(sourceRoot: File): List<ModuleApiScanResult> {
        val endpointRoot = sourceRoot.canonicalFile
        val endpointModules = discoverEndpointModules(endpointRoot)
        val modelRoots = discoverModelRoots(endpointRoot, endpointModules)
        val classInfos = readSourceClasses(modelRoots)

        val index = SourceModelIndex(classInfos)
        return endpointModules.mapNotNull { module ->
            val contextPath = ContextPathReader.readContextPath(module.sourceRoot)
            if (contextPath.isNotBlank()) {
                System.err.println("Module ${module.name}: context-path = $contextPath")
            }
            val endpoints = classInfos
                .filter { it.isController }
                .filter { it.file.isUnder(module.sourceRoot) }
                .flatMap { buildEndpoints(it, index, contextPath) }
            if (endpoints.isEmpty()) return@mapNotNull null
            ModuleApiScanResult(
                moduleName = module.name,
                sourceRoot = module.sourceRoot,
                endpoints = endpoints
            )
        }
    }

    private fun readSourceClasses(modelRoots: List<File>): List<SourceClassInfo> {
        val javaFiles = modelRoots.asSequence()
            .flatMap { root -> root.walkTopDown() }
            .filter { it.isFile && it.extension == "java" }
            .distinctBy { it.canonicalPath }
            .toList()

        val parser = JavaParser()
        val classInfos = mutableListOf<SourceClassInfo>()
        for (file in javaFiles) {
            val result = parser.parse(file)
            val cu = result.result.orElse(null) ?: continue
            val packageName = cu.packageDeclaration.map { it.nameAsString }.orElse("")
            val imports = cu.imports.map { if (it.isAsterisk) "${it.nameAsString}.*" else it.nameAsString }
            cu.findAll(ClassOrInterfaceDeclaration::class.java).forEach { clazz ->
                if (clazz.isNestedType) return@forEach
                classInfos.add(readClass(file, cu, packageName, imports, clazz))
            }
        }
        return classInfos
    }

    private fun discoverEndpointModules(sourceRoot: File): List<SourceModuleRoot> {
        if (File(sourceRoot, "pom.xml").isFile) {
            val modules = readMavenModules(sourceRoot).mapNotNull { module ->
                val moduleDir = File(sourceRoot, module.replace('/', File.separatorChar)).canonicalFile
                val javaRoot = File(moduleDir, "src/main/java").canonicalFile
                if (javaRoot.isDirectory) SourceModuleRoot(moduleDir.name, javaRoot) else null
            }
            if (modules.isNotEmpty()) return modules
        }

        val javaRoot = File(sourceRoot, "src/main/java").canonicalFile
        if (javaRoot.isDirectory) {
            return listOf(SourceModuleRoot(sourceRoot.name, javaRoot))
        }

        val moduleRoot = findNearestPomDir(sourceRoot)
        return listOf(SourceModuleRoot(moduleRoot?.name ?: sourceRoot.name, sourceRoot))
    }

    private fun discoverModelRoots(sourceRoot: File, endpointModules: List<SourceModuleRoot>): List<File> {
        val roots = linkedSetOf<File>()
        endpointModules.forEach { roots.add(it.sourceRoot) }
        val moduleRoot = findNearestPomDir(sourceRoot)
        val aggregatorRoot = moduleRoot?.let { findNearestAggregatorPomDir(it) }
        if (aggregatorRoot != null) {
            readMavenModules(aggregatorRoot).forEach { module ->
                val javaRoot = File(aggregatorRoot, module.replace('/', File.separatorChar))
                    .resolve("src/main/java")
                    .canonicalFile
                if (javaRoot.isDirectory) roots.add(javaRoot)
            }
        }
        discoverDependencySourceRoots(sourceRoot, endpointModules, moduleRoot, aggregatorRoot).forEach { roots.add(it) }
        return roots.toList()
    }

    private fun discoverDependencySourceRoots(
        sourceRoot: File,
        endpointModules: List<SourceModuleRoot>,
        moduleRoot: File?,
        aggregatorRoot: File?
    ): List<File> {
        val pomDirs = linkedSetOf<File>()
        moduleRoot?.let { pomDirs.add(it) }
        aggregatorRoot?.let { root ->
            pomDirs.add(root)
            readMavenModules(root).forEach { module ->
                val moduleDir = File(root, module.replace('/', File.separatorChar)).canonicalFile
                if (File(moduleDir, "pom.xml").isFile) pomDirs.add(moduleDir)
            }
        }
        endpointModules.mapNotNullTo(pomDirs) { findNearestPomDir(it.sourceRoot) }

        val localModuleNames = localModuleNames(sourceRoot, endpointModules, moduleRoot, aggregatorRoot)
        val artifactIds = pomDirs
            .flatMapTo(linkedSetOf()) { readMavenArtifactIds(it) }
            .filterNot { it in localModuleNames }
            .toSet()
        if (artifactIds.isEmpty()) return emptyList()

        val searchBases = linkedSetOf<File>()
        aggregatorRoot?.parentFile?.canonicalFile?.let { searchBases.add(it) }
        moduleRoot?.parentFile?.canonicalFile?.let { searchBases.add(it) }
        sourceRoot.parentFile?.canonicalFile?.let { searchBases.add(it) }

        return searchBases
            .flatMap { findArtifactSourceRoots(it, artifactIds) }
            .distinctBy { it.canonicalPath }
    }

    private fun localModuleNames(
        sourceRoot: File,
        endpointModules: List<SourceModuleRoot>,
        moduleRoot: File?,
        aggregatorRoot: File?
    ): Set<String> {
        val names = linkedSetOf(sourceRoot.name)
        moduleRoot?.name?.let { names.add(it) }
        aggregatorRoot?.name?.let { names.add(it) }
        endpointModules.mapTo(names) { it.name }
        aggregatorRoot?.let { root ->
            readMavenModules(root).forEach { module ->
                names.add(File(root, module.replace('/', File.separatorChar)).name)
            }
        }
        return names
    }

    private fun findNearestPomDir(start: File): File? {
        var current: File? = if (start.isFile) start.parentFile else start
        while (current != null) {
            if (File(current, "pom.xml").isFile) return current.canonicalFile
            current = current.parentFile
        }
        return null
    }

    private fun findNearestAggregatorPomDir(moduleRoot: File): File? {
        var current: File? = moduleRoot
        while (current != null) {
            val pom = File(current, "pom.xml")
            if (pom.isFile && readMavenModules(current).isNotEmpty()) {
                return current.canonicalFile
            }
            current = current.parentFile
        }
        return null
    }

    private fun readMavenModules(root: File): List<String> {
        val pom = File(root, "pom.xml")
        if (!pom.isFile) return emptyList()
        return Regex("<module>\\s*([^<]+?)\\s*</module>")
            .findAll(pom.readText())
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun readMavenArtifactIds(root: File): List<String> {
        val pom = File(root, "pom.xml")
        if (!pom.isFile) return emptyList()
        return Regex("<artifactId>\\s*([^<]+?)\\s*</artifactId>")
            .findAll(pom.readText())
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && !it.contains('$') }
            .distinct()
            .toList()
    }

    private fun findArtifactSourceRoots(base: File, artifactIds: Set<String>): List<File> {
        if (!base.isDirectory) return emptyList()
        val roots = mutableListOf<File>()
        val queue = mutableListOf(base.canonicalFile to 0)
        var index = 0
        while (index < queue.size) {
            val (dir, depth) = queue[index++]
            if (dir.name in SKIPPED_DISCOVERY_DIRS && depth > 0) continue

            val javaRoot = File(dir, "src/main/java").canonicalFile
            if (dir.name in artifactIds && javaRoot.isDirectory) {
                roots.add(javaRoot)
                continue
            }

            if (depth >= MAX_DEPENDENCY_DISCOVERY_DEPTH) continue
            dir.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory }
                ?.forEach { queue.add(it.canonicalFile to depth + 1) }
        }
        return roots
    }

    private fun readClass(
        file: File,
        cu: CompilationUnit,
        packageName: String,
        imports: List<String>,
        clazz: ClassOrInterfaceDeclaration
    ): SourceClassInfo {
        val fqn = if (packageName.isBlank()) clazz.nameAsString else "$packageName.${clazz.nameAsString}"
        val basePath = firstAnnotationValue(clazz.annotations, "RequestMapping", "value", "path")
        val groupName = firstAnnotationValue(clazz.annotations, "Api", "tags", "value")
            .ifBlank { firstAnnotationValue(clazz.annotations, "Tag", "name") }
        val description = firstAnnotationValue(clazz.annotations, "Api", "description")
            .ifBlank { firstAnnotationValue(clazz.annotations, "Tag", "description") }

        val fields = clazz.fields
            .filterNot { it.isStatic || it.isTransient || it.annotations.any { ann -> ann.nameAsString == "JsonIgnore" } }
            .flatMap { field -> field.variables.map { variable ->
            SourceFieldInfo(
                name = annotationValue(field.annotations, "ApiModelProperty", "name")
                    .ifBlank { annotationValue(field.annotations, "Schema", "name") }
                    .ifBlank { variable.nameAsString },
                type = variable.type,
                description = annotationValue(field.annotations, "ApiModelProperty", "value")
                    .ifBlank { annotationValue(field.annotations, "Schema", "description") },
                required = annotationBoolean(field.annotations, "ApiModelProperty", "required")
                    || annotationValue(field.annotations, "Schema", "requiredMode").contains("REQUIRED")
                    || field.annotations.any { it.nameAsString in REQUIRED_ANNOTATIONS },
                example = annotationValue(field.annotations, "ApiModelProperty", "example")
                    .ifBlank { annotationValue(field.annotations, "Schema", "example") }
            )
        } }

        return SourceClassInfo(
            file = file,
            packageName = packageName,
            imports = imports,
            simpleName = clazz.nameAsString,
            fullyQualifiedName = fqn,
            typeParameters = clazz.typeParameters.map(TypeParameter::getNameAsString),
            isController = clazz.annotations.any { it.nameAsString in CONTROLLER_ANNOTATIONS },
            isHidden = clazz.annotations.any { it.nameAsString == "Hidden" },
            basePath = normalizePath(basePath),
            groupName = groupName,
            description = description,
            fields = fields,
            superTypes = clazz.extendedTypes.toList(),
            methods = clazz.methods.toList()
        )
    }

    private fun buildEndpoints(classInfo: SourceClassInfo, index: SourceModelIndex, contextPath: String = ""): List<ApiEndpoint> {
        if (classInfo.isHidden) return emptyList()
        val endpoints = mutableListOf<ApiEndpoint>()
        for (method in classInfo.methods) {
            if (method.annotations.any { it.nameAsString == "Hidden" }) continue
            val mapping = methodMapping(method) ?: continue
            val paths = mapping.paths.ifEmpty { listOf("") }
            for (path in paths) {
                val fullPath = prependContextPath(contextPath, buildPath(classInfo.basePath, path))
                val methodName = annotationValue(method.annotations, "ApiOperation", "value")
                    .ifBlank { annotationValue(method.annotations, "Operation", "summary") }
                    .ifBlank { method.nameAsString }
                val description = annotationValue(method.annotations, "ApiOperation", "notes")
                    .ifBlank { annotationValue(method.annotations, "Operation", "description") }

                val params = method.parameters
                    .filterNot { it.annotations.any { ann -> ann.nameAsString == "RequestBody" } }
                    .flatMap { toApiParameters(it, classInfo, index) }
                val body = method.parameters.firstOrNull { it.annotations.any { ann -> ann.nameAsString == "RequestBody" } }
                    ?.let { index.resolve(it.type, classInfo, emptyMap()) }
                val response = index.resolve(method.type, classInfo, emptyMap())

                endpoints.add(
                    ApiEndpoint(
                        name = methodName,
                        folder = classInfo.groupName.ifBlank { classInfo.simpleName },
                        description = description.ifBlank { classInfo.description },
                        className = classInfo.fullyQualifiedName,
                        classDescription = classInfo.description,
                        metadata = HttpMetadata(
                            path = fullPath,
                            method = mapping.method,
                            parameters = params,
                            body = body,
                            responseBody = response
                        )
                    )
                )
            }
        }
        return endpoints
    }

    private fun methodMapping(method: MethodDeclaration): SourceMapping? {
        for (annotation in method.annotations) {
            val httpMethod = when (annotation.nameAsString) {
                "GetMapping" -> HttpMethod.GET
                "PostMapping" -> HttpMethod.POST
                "PutMapping" -> HttpMethod.PUT
                "DeleteMapping" -> HttpMethod.DELETE
                "PatchMapping" -> HttpMethod.PATCH
                "RequestMapping" -> requestMappingMethod(annotation)
                else -> null
            } ?: continue
            val paths = annotationValues(annotation, "value", "path").map(::normalizePath)
            return SourceMapping(httpMethod, paths)
        }
        return null
    }

    private fun requestMappingMethod(annotation: AnnotationExpr): HttpMethod {
        val methods = annotationValues(annotation, "method")
        val raw = methods.firstOrNull().orEmpty()
        return when {
            raw.endsWith("GET") -> HttpMethod.GET
            raw.endsWith("POST") -> HttpMethod.POST
            raw.endsWith("PUT") -> HttpMethod.PUT
            raw.endsWith("DELETE") -> HttpMethod.DELETE
            raw.endsWith("PATCH") -> HttpMethod.PATCH
            else -> HttpMethod.NO_METHOD
        }
    }

    private fun toApiParameters(parameter: Parameter, classInfo: SourceClassInfo, index: SourceModelIndex): List<ApiParameter> {
        val binding = when {
            parameter.annotations.any { it.nameAsString == "PathVariable" } -> ParameterBinding.Path
            parameter.annotations.any { it.nameAsString == "RequestHeader" } -> ParameterBinding.Header
            parameter.annotations.any { it.nameAsString == "CookieValue" } -> ParameterBinding.Cookie
            else -> ParameterBinding.Query
        }
        val springName = firstAnnotationValue(parameter.annotations, "RequestParam", "value", "name")
            .ifBlank { firstAnnotationValue(parameter.annotations, "PathVariable", "value", "name") }
            .ifBlank { firstAnnotationValue(parameter.annotations, "RequestHeader", "value", "name") }
            .ifBlank { firstAnnotationValue(parameter.annotations, "CookieValue", "value", "name") }
        val apiName = firstAnnotationValue(parameter.annotations, "ApiParam", "name")
            .ifBlank { firstAnnotationValue(parameter.annotations, "Parameter", "name") }
        val desc = firstAnnotationValue(parameter.annotations, "ApiParam", "value")
            .ifBlank { firstAnnotationValue(parameter.annotations, "Parameter", "description") }
        val defaultValue = firstAnnotationValue(parameter.annotations, "RequestParam", "defaultValue")
        val requestParamRequired = hasAnnotation(parameter.annotations, "RequestParam")
            && annotationValue(parameter.annotations, "RequestParam", "required") != "false"
            && defaultValue.isBlank()
        val required = requestParamRequired
            || annotationBoolean(parameter.annotations, "PathVariable", "required")
            || annotationBoolean(parameter.annotations, "ApiParam", "required")
            || annotationBoolean(parameter.annotations, "Parameter", "required")
            || binding == ParameterBinding.Path
        val schema = index.resolve(parameter.type, classInfo, emptyMap())
        if (binding == ParameterBinding.Query && shouldExpandQueryObject(parameter, schema)) {
            return expandQueryObject(schema as ObjectModel.Object)
        }
        return listOf(ApiParameter(
            name = apiName.ifBlank { springName }.ifBlank { parameter.nameAsString },
            binding = binding,
            required = required,
            description = desc,
            example = firstAnnotationValue(parameter.annotations, "ApiParam", "example")
                .ifBlank { firstAnnotationValue(parameter.annotations, "Parameter", "example") },
            defaultValue = defaultValue,
            schema = schema
        ))
    }

    private fun shouldExpandQueryObject(parameter: Parameter, schema: ObjectModel?): Boolean {
        if (schema !is ObjectModel.Object) return false
        if (parameter.annotations.any { it.nameAsString in setOf("RequestParam", "PathVariable", "RequestHeader", "CookieValue") }) {
            return false
        }
        return schema.fields.isNotEmpty()
    }

    private fun expandQueryObject(model: ObjectModel.Object): List<ApiParameter> {
        return model.fields.map { (name, field) ->
            ApiParameter(
                name = name,
                binding = ParameterBinding.Query,
                required = field.required,
                description = field.comment,
                example = field.demo?.toString().orEmpty(),
                defaultValue = field.defaultValue,
                schema = field.model
            )
        }
    }

    private fun buildPath(basePath: String, methodPath: String): String {
        val base = basePath.trim('/')
        val method = methodPath.trim('/')
        return when {
            base.isBlank() && method.isBlank() -> "/"
            base.isBlank() -> "/$method"
            method.isBlank() -> "/$base"
            else -> "/$base/$method"
        }
    }

    private fun prependContextPath(contextPath: String, path: String): String {
        if (contextPath.isBlank()) return path
        val prefix = contextPath.trim('/')
        if (prefix.isBlank()) return path
        return "/$prefix${path}"
    }

    private fun normalizePath(path: String): String {
        return path.trim().removeSurrounding("\"").let {
            if (it.isBlank()) "" else "/${it.trimStart('/')}"
        }
    }

    companion object {
        private val CONTROLLER_ANNOTATIONS = setOf("RestController", "Controller", "Api", "Tag", "FeignClient")
        private val REQUIRED_ANNOTATIONS = setOf("NotNull", "NotEmpty", "NotBlank")
        private val SKIPPED_DISCOVERY_DIRS = setOf(".git", ".gradle", ".idea", "build", "target", "out", "node_modules")
        private const val MAX_DEPENDENCY_DISCOVERY_DEPTH = 5
    }
}

private class SourceModelIndex(private val classes: List<SourceClassInfo>) {
    private val byFqn = classes.associateBy { it.fullyQualifiedName }
    private val bySimple = classes.groupBy { it.simpleName }
    private val resolving = mutableSetOf<String>()

    fun resolve(type: Type, context: SourceClassInfo, typeVars: Map<String, Type>): ObjectModel? {
        return when {
            type is VoidType -> null
            type is PrimitiveType -> ObjectModel.Single(type = primitiveName(type))
            type is ArrayType -> resolve(type.componentType, context, typeVars)?.let { ObjectModel.Array(it) }
            type is WildcardType -> type.extendedType.orElse(null)?.let { resolve(it, context, typeVars) } ?: ObjectModel.Single("object")
            type is ClassOrInterfaceType -> resolveClassType(type, context, typeVars)
            else -> ObjectModel.Single(type = type.asString())
        }
    }

    private fun resolveClassType(type: ClassOrInterfaceType, context: SourceClassInfo, typeVars: Map<String, Type>): ObjectModel? {
        val simpleName = type.nameAsString.substringAfterLast('.')
        typeVars[simpleName]
            ?.takeIf { it.asString() != type.asString() }
            ?.let { return resolve(it, context, typeVars) }
        primitiveName(simpleName)?.let { return ObjectModel.Single(it) }

        if (simpleName in COLLECTION_TYPES) {
            val itemType = typeArgs(type).firstOrNull()
            return ObjectModel.Array(itemType?.let { resolve(it, context, typeVars) } ?: ObjectModel.Single("object"))
        }
        if (simpleName in MAP_TYPES) {
            val args = typeArgs(type)
            val valueType = args.getOrNull(1)?.let { resolve(it, context, typeVars) } ?: ObjectModel.Single("object")
            return ObjectModel.MapModel(ObjectModel.Single("string"), valueType)
        }
        val args = typeArgs(type)
        val classInfo = findClass(type, context)
            ?: return unknownGenericFallback(simpleName, args, context, typeVars)
                ?: ObjectModel.Single(simpleName)
        val qualifiedArgs = args.map { qualifyType(it, context, typeVars) }
        val scopedVars = classInfo.typeParameters
            .zip(qualifiedArgs)
            .toMap()
        val cacheKey = classInfo.fullyQualifiedName + qualifiedArgs.joinToString(prefix = "<", postfix = ">") { it.asString() }
        if (!resolving.add(cacheKey)) return ObjectModel.Single("object")

        val fields = collectFields(classInfo, scopedVars).associate { resolvedField ->
            val field = resolvedField.field
            val fieldModel = resolve(field.type, resolvedField.owner, resolvedField.typeVars) ?: ObjectModel.Single(field.type.asString())
            field.name to FieldModel(
                model = fieldModel,
                comment = field.description,
                required = field.required,
                demo = field.example
            )
        }
        resolving.remove(cacheKey)
        return ObjectModel.Object(fields = fields, id = cacheKey)
    }

    private fun collectFields(classInfo: SourceClassInfo, typeVars: Map<String, Type>): List<ResolvedSourceField> {
        val inherited = classInfo.superTypes.flatMap { superType ->
            val superClass = findClass(superType, classInfo) ?: return@flatMap emptyList()
            val superArgs = typeArgs(superType)
            val superVars = typeVars + superClass.typeParameters
                .zip(superArgs.map { substituteTypeVars(it, typeVars) })
                .toMap()
            collectFields(superClass, superVars)
        }
        return inherited + classInfo.fields.map { ResolvedSourceField(it, classInfo, typeVars) }
    }

    private fun unknownGenericFallback(
        simpleName: String,
        args: List<Type>,
        context: SourceClassInfo,
        typeVars: Map<String, Type>
    ): ObjectModel? {
        if (args.isEmpty()) return null
        // For unknown generic types with a single type argument, try to resolve the
        // type argument directly. This handles cases where the source code for a generic
        // wrapper (e.g. Resp<T>, PageResp<T>) is available but not discovered by the
        // scanner. Instead of fabricating fields, we transparently unwrap to the argument.
        if (args.size == 1) {
            val resolved = resolve(substituteTypeVars(args.first(), typeVars), context, typeVars)
            if (resolved != null) return resolved
        }
        return null
    }

    private fun substituteTypeVars(type: Type, typeVars: Map<String, Type>): Type {
        if (type is ClassOrInterfaceType && type.scope.isEmpty && type.typeArguments.isEmpty) {
            return typeVars[type.nameAsString]?.takeIf { it.asString() != type.asString() } ?: type
        }
        if (type is ClassOrInterfaceType && type.typeArguments.isPresent) {
            val cloned = type.clone()
            val substitutedArgs = typeArgs(type).map { substituteTypeVars(it, typeVars) }
            cloned.setTypeArguments(NodeList(substitutedArgs))
            return cloned
        }
        if (type is ArrayType) {
            return ArrayType(substituteTypeVars(type.componentType, typeVars))
        }
        return type
    }

    private fun qualifyType(type: Type, context: SourceClassInfo, typeVars: Map<String, Type>): Type {
        val substituted = substituteTypeVars(type, typeVars)
        if (substituted is ClassOrInterfaceType) {
            val cloned = substituted.clone()
            if (cloned.scope.isEmpty && cloned.typeArguments.isEmpty && !typeVars.containsKey(cloned.nameAsString)) {
                findClass(cloned, context)?.let { return ClassOrInterfaceType(null, it.fullyQualifiedName) }
            }
            if (cloned.typeArguments.isPresent) {
                cloned.setTypeArguments(NodeList(typeArgs(cloned).map { qualifyType(it, context, typeVars) }))
            }
            return cloned
        }
        if (substituted is ArrayType) {
            return ArrayType(qualifyType(substituted.componentType, context, typeVars))
        }
        return substituted
    }

    private fun typeArgs(type: ClassOrInterfaceType): List<Type> {
        return type.typeArguments.map { it.toList() }.orElse(emptyList())
    }

    private fun findClass(type: ClassOrInterfaceType, context: SourceClassInfo): SourceClassInfo? {
        val typeText = type.asString()
        val simpleName = type.nameAsString.substringAfterLast('.')
        if (typeText.contains('.')) {
            byFqn[typeText.substringBefore('<')]?.let { return it }
        }
        val imported = context.imports.firstOrNull { it.endsWith(".$simpleName") }
        if (imported != null) return byFqn[imported]
        for (wildcardImport in context.imports.filter { it.endsWith(".*") }) {
            byFqn["${wildcardImport.removeSuffix(".*")}.$simpleName"]?.let { return it }
        }
        val samePackage = if (context.packageName.isBlank()) simpleName else "${context.packageName}.$simpleName"
        return byFqn[samePackage] ?: bySimple[simpleName]?.firstOrNull()
    }

    private fun primitiveName(type: PrimitiveType): String = when (type.type) {
        PrimitiveType.Primitive.BOOLEAN -> "boolean"
        PrimitiveType.Primitive.BYTE -> "byte"
        PrimitiveType.Primitive.CHAR -> "char"
        PrimitiveType.Primitive.DOUBLE -> "double"
        PrimitiveType.Primitive.FLOAT -> "float"
        PrimitiveType.Primitive.INT -> "int"
        PrimitiveType.Primitive.LONG -> "long"
        PrimitiveType.Primitive.SHORT -> "short"
    }

    private fun primitiveName(simpleName: String): String? = when (simpleName) {
        "String", "CharSequence", "Character", "char" -> "string"
        "Integer", "int" -> "int"
        "Long", "long" -> "long"
        "Boolean", "boolean" -> "boolean"
        "Double", "double", "BigDecimal" -> "double"
        "Float", "float" -> "float"
        "Byte", "byte" -> "byte"
        "Short", "short" -> "short"
        "Date", "LocalDate", "LocalDateTime", "Instant" -> "datetime"
        "Void", "void" -> "void"
        else -> null
    }

    companion object {
        private val COLLECTION_TYPES = setOf("List", "Set", "Collection", "ArrayList", "HashSet", "LinkedList")
        private val MAP_TYPES = setOf("Map", "HashMap", "LinkedHashMap", "TreeMap")
    }
}

private data class SourceClassInfo(
    val file: File,
    val packageName: String,
    val imports: List<String>,
    val simpleName: String,
    val fullyQualifiedName: String,
    val typeParameters: List<String>,
    val isController: Boolean,
    val isHidden: Boolean,
    val basePath: String,
    val groupName: String,
    val description: String,
    val fields: List<SourceFieldInfo>,
    val superTypes: List<ClassOrInterfaceType>,
    val methods: List<MethodDeclaration>
)

private data class SourceFieldInfo(
    val name: String,
    val type: Type,
    val description: String,
    val required: Boolean,
    val example: String
)

private data class ResolvedSourceField(
    val field: SourceFieldInfo,
    val owner: SourceClassInfo,
    val typeVars: Map<String, Type>
)

private data class SourceMapping(
    val method: HttpMethod,
    val paths: List<String>
)

private data class SourceModuleRoot(
    val name: String,
    val sourceRoot: File
)

private fun firstAnnotationValue(annotations: List<AnnotationExpr>, annotationName: String, vararg names: String): String {
    for (name in names) {
        val value = annotationValue(annotations, annotationName, name)
        if (value.isNotBlank()) return value
    }
    return ""
}

private fun annotationValue(annotations: List<AnnotationExpr>, annotationName: String, name: String): String {
    val annotation = annotations.firstOrNull { it.nameAsString == annotationName } ?: return ""
    return annotationValues(annotation, name).firstOrNull().orEmpty()
}

private fun annotationBoolean(annotations: List<AnnotationExpr>, annotationName: String, name: String): Boolean {
    return annotationValue(annotations, annotationName, name).equals("true", ignoreCase = true)
}

private fun hasAnnotation(annotations: List<AnnotationExpr>, annotationName: String): Boolean {
    return annotations.any { it.nameAsString == annotationName }
}

private fun annotationValues(annotation: AnnotationExpr, vararg names: String): List<String> {
    val values = mutableListOf<String>()
    when {
        annotation.isSingleMemberAnnotationExpr && ("value" in names || names.isEmpty()) -> {
            values.addAll(expressionValues(annotation.asSingleMemberAnnotationExpr().memberValue))
        }
        annotation.isNormalAnnotationExpr -> {
            val pairs = annotation.asNormalAnnotationExpr().pairs
            for (pair: MemberValuePair in pairs) {
                if (pair.nameAsString in names) {
                    values.addAll(expressionValues(pair.value))
                }
            }
        }
    }
    return values
}

private fun expressionValues(expression: Expression): List<String> {
    return when {
        expression is ArrayInitializerExpr -> expression.values.flatMap(::expressionValues)
        expression.isStringLiteralExpr -> listOf(expression.asStringLiteralExpr().asString())
        expression.isNameExpr -> listOf(expression.asNameExpr().nameAsString)
        expression.isFieldAccessExpr -> listOf(expression.asFieldAccessExpr().nameAsString)
        expression.isClassExpr -> listOf(expression.asClassExpr().type.asString())
        expression.isBooleanLiteralExpr -> listOf(expression.asBooleanLiteralExpr().value.toString())
        else -> listOf(expression.toString().trim('"'))
    }
}

private fun File.isUnder(root: File): Boolean {
    val filePath = canonicalFile.toPath()
    val rootPath = root.canonicalFile.toPath()
    return filePath == rootPath || filePath.startsWith(rootPath)
}
