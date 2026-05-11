package com.itangcent.easyapi.scanner

import com.itangcent.easyapi.scanner.formatter.OpenApiJsonFormatter
import com.itangcent.easyapi.scanner.apifox.ApifoxClient
import com.itangcent.easyapi.scanner.source.ModuleApiScanResult
import com.itangcent.easyapi.scanner.source.SourceApiScanner
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

fun main(args: Array<String>) {
    val config = parseArgs(args)

    if (config.sourcePath == null || config.showHelp) {
        printUsage()
        return
    }

    val sourceRoot = File(config.sourcePath)
    if (!sourceRoot.exists() || !sourceRoot.isDirectory) {
        System.err.println("Error: source directory not found: ${config.sourcePath}")
        System.exit(1)
    }

    System.err.println("Scanning source directory: ${sourceRoot.absolutePath}")
    val scanResults = SourceApiScanner().scanModules(sourceRoot)
    val endpoints = scanResults.flatMap { it.endpoints }
    System.err.println("Total modules: ${scanResults.size}")
    System.err.println("Total endpoints: ${endpoints.size}")

    if (endpoints.isEmpty()) {
        System.err.println("No API endpoints found. The source directory may not contain Spring MVC controllers.")
    }

    val formatter = OpenApiJsonFormatter()
    val writeAsModuleFiles = config.outputPath != null
        && (scanResults.size > 1 || (isDirectoryOutputPath(config.outputPath) && isMultiModuleSource(sourceRoot)))

    val generatedDocuments = if (writeAsModuleFiles) {
        writeModuleJsonFiles(scanResults, config, formatter)
    } else if (scanResults.size > 1) {
        if (config.outputPath == null) {
            System.err.println("Error: multiple modules found. Please provide -o <output-dir> or -o <base-file.json>.")
            System.exit(1)
        }
        emptyList()
    } else if (config.outputPath != null) {
        val moduleName = config.moduleName ?: scanResults.firstOrNull()?.moduleName ?: sourceRoot.name
        val openApiJson = formatter.format(endpoints, moduleName)
        val outputFile = File(config.outputPath)
        outputFile.parentFile?.mkdirs()
        writeIncrementalDocument(moduleName, moduleName, openApiJson, outputFile)?.let { listOf(it) }.orEmpty()
    } else {
        val moduleName = config.moduleName ?: scanResults.firstOrNull()?.moduleName ?: sourceRoot.name
        val openApiJson = formatter.format(endpoints, moduleName)
        println(openApiJson)
        listOf(GeneratedOpenApiDocument(moduleName, moduleName, openApiJson, null))
    }

    uploadToApifoxIfConfigured(config, generatedDocuments)
}

data class ModuleTarget(val projectId: String?, val moduleId: Long?)

data class CliConfig(
    val sourcePath: String? = null,
    val outputPath: String? = null,
    val moduleName: String? = null,
    val apifoxToken: String? = null,
    val apifoxProjectId: String? = null,
    val apifoxModuleTargets: Map<String, ModuleTarget> = emptyMap(),
    val apifoxBaseUrl: String = "https://api.apifox.com",
    val apifoxApiVersion: String = "2024-03-28",
    val apifoxUrlPrefix: String? = null,
    val showHelp: Boolean = false
)

private fun parseArgs(args: Array<String>): CliConfig {
    var sourcePath: String? = null
    var outputPath: String? = null
    var moduleName: String? = null
    var apifoxToken: String? = null
    var apifoxProjectId: String? = null
    val apifoxModuleTargets = linkedMapOf<String, ModuleTarget>()
    var apifoxBaseUrl = "https://api.apifox.com"
    var apifoxApiVersion = "2024-03-28"
    var apifoxUrlPrefix: String? = null
    var showHelp = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "-h", "--help" -> {
                showHelp = true
                i++
            }
            "-o", "--output" -> {
                outputPath = args.getOrNull(++i)
                i++
            }
            "--module" -> {
                moduleName = args.getOrNull(++i)
                i++
            }
            "--apifox-token" -> {
                apifoxToken = args.getOrNull(++i)
                i++
            }
            "--apifox-project" -> {
                apifoxProjectId = args.getOrNull(++i)
                i++
            }
            "--apifox-module" -> {
                parseModuleMapping(args.getOrNull(++i))?.let { (name, target) ->
                    apifoxModuleTargets[name] = target
                }
                i++
            }
            "--apifox-base-url" -> {
                apifoxBaseUrl = args.getOrNull(++i) ?: apifoxBaseUrl
                i++
            }
            "--apifox-api-version" -> {
                apifoxApiVersion = args.getOrNull(++i) ?: apifoxApiVersion
                i++
            }
            "--apifox-url-prefix" -> {
                apifoxUrlPrefix = args.getOrNull(++i)
                i++
            }
            else -> {
                if (sourcePath == null) sourcePath = args[i]
                i++
            }
        }
    }

    return CliConfig(
        sourcePath = sourcePath,
        outputPath = outputPath,
        moduleName = moduleName,
        apifoxToken = apifoxToken ?: System.getenv("APIFOX_TOKEN"),
        apifoxProjectId = apifoxProjectId ?: System.getenv("APIFOX_PROJECT_ID"),
        apifoxModuleTargets = apifoxModuleTargets,
        apifoxBaseUrl = apifoxBaseUrl,
        apifoxApiVersion = apifoxApiVersion,
        apifoxUrlPrefix = apifoxUrlPrefix,
        showHelp = showHelp
    )
}

private fun printUsage() {
    println(
        """
        Easy API Scanner - Scan Java source code and generate OpenAPI JSON

        Usage:
          java -jar easy-api-scanner.jar <source-dir> [options]

        Options:
          -o, --output <path>      Output file path, or output dir/base file for multi-module scans
          --module <name>          Module name for the OpenAPI title
          --apifox-token <token>   Apifox access token (or APIFOX_TOKEN)
          --apifox-project <id>    Apifox project id (or APIFOX_PROJECT_ID)
          --apifox-module <m=[pid:]id> Module name to Apifox project id and module id mapping, repeatable
          --apifox-base-url <url>  Apifox API base URL (default: https://api.apifox.com)
          --apifox-url-prefix <u>  URL prefix for Apifox import (Apifox fetches JSON from this URL + filename)
          -h, --help               Show this help message

        Incremental output:
          If an output JSON already exists, only new or changed path/method operations are written to *-new.json.
          Apifox upload uses the generated *-new.json document for modules with existing output JSON.

        Examples:
          java -jar easy-api-scanner.jar D:\project\my-app\src\main\java -o openapi.json
          java -jar easy-api-scanner.jar D:\project\my-app --module "My Service" -o openapi.json
          java -jar easy-api-scanner.jar D:\project\multi-module-app -o D:\docs\openapi
          java -jar easy-api-scanner.jar D:\project\multi-module-app -o D:\docs\openapi --apifox-project 123 --apifox-token APS-xxx --apifox-module user-api=456
          java -jar easy-api-scanner.jar D:\project\my-app -o D:\docs\openapi.json --apifox-project 123 --apifox-token APS-xxx --apifox-module my-api=123:456 --apifox-url-prefix https://cdn.example.com/docs
    """.trimIndent()
    )
}

private fun writeModuleJsonFiles(
    scanResults: List<ModuleApiScanResult>,
    config: CliConfig,
    formatter: OpenApiJsonFormatter
): List<GeneratedOpenApiDocument> {
    val outputPath = File(config.outputPath!!)
    val outputIsBaseFile = outputPath.extension.equals("json", ignoreCase = true)
    val outputDir = if (outputIsBaseFile) {
        outputPath.parentFile ?: File(".")
    } else {
        outputPath
    }
    outputDir.mkdirs()

    val usedNames = mutableSetOf<String>()
    val documents = mutableListOf<GeneratedOpenApiDocument>()
    for (result in scanResults) {
        val moduleFileName = safeFileName(result.moduleName)
        val fileName = if (outputIsBaseFile) {
            "${outputPath.nameWithoutExtension}-$moduleFileName.json"
        } else {
            "$moduleFileName.json"
        }
        val outputFile = uniqueFile(outputDir, fileName, usedNames)
        val title = config.moduleName?.let { "$it - ${result.moduleName}" } ?: result.moduleName
        val openApiJson = formatter.format(result.endpoints, title)
        writeIncrementalDocument(result.moduleName, title, openApiJson, outputFile)?.let { document ->
            documents.add(document)
            System.err.println("Module ${result.moduleName}: ${result.endpoints.size} endpoints -> ${document.file?.absolutePath}")
        }
    }
    return documents
}

private fun safeFileName(name: String): String {
    return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "module" }
}

private fun uniqueFile(dir: File, preferredName: String, usedNames: MutableSet<String>): File {
    var name = preferredName
    val base = preferredName.substringBeforeLast('.', preferredName)
    val ext = preferredName.substringAfterLast('.', "")
    var index = 2
    while (!usedNames.add(name)) {
        name = if (ext.isBlank()) "$base-$index" else "$base-$index.$ext"
        index++
    }
    return File(dir, name)
}

private fun isDirectoryOutputPath(path: String): Boolean {
    val file = File(path)
    return file.isDirectory || file.extension.isBlank()
}

private fun isMultiModuleSource(sourceRoot: File): Boolean {
    val pom = File(sourceRoot, "pom.xml")
    if (!pom.isFile) return false
    return Regex("<module>\\s*([^<]+?)\\s*</module>").findAll(pom.readText()).count() > 1
}

private fun parseModuleMapping(value: String?): Pair<String, ModuleTarget>? {
    if (value.isNullOrBlank()) return null
    val separator = if ('=' in value) '=' else ':'
    val name = value.substringBefore(separator).trim()
    val remainder = value.substringAfter(separator, "").trim()
    
    val projectId: String?
    val moduleId: Long?
    if (':' in remainder) {
        projectId = remainder.substringBefore(':').trim().takeIf { it.isNotBlank() }
        moduleId = remainder.substringAfter(':').trim().toLongOrNull()
    } else {
        projectId = remainder.takeIf { it.isNotBlank() }
        moduleId = null
    }
    
    if (name.isBlank() || (projectId == null && moduleId == null)) {
        System.err.println("Invalid --apifox-module value: $value, expected moduleName=projectId[:moduleId] or moduleName=:moduleId or moduleName=projectId")
        return null
    }
    return name to ModuleTarget(projectId, moduleId)
}

private fun uploadToApifoxIfConfigured(config: CliConfig, documents: List<GeneratedOpenApiDocument>) {
    val token = config.apifoxToken
    val defaultProjectId = config.apifoxProjectId
    if (token.isNullOrBlank() && defaultProjectId.isNullOrBlank() && config.apifoxModuleTargets.isEmpty()) return
    if (token.isNullOrBlank()) {
        System.err.println("Apifox upload skipped: --apifox-token is required.")
        return
    }
    if (documents.isEmpty()) {
        System.err.println("Apifox upload skipped: no generated OpenAPI documents.")
        return
    }

    val client = ApifoxClient(token, config.apifoxBaseUrl, config.apifoxApiVersion)
    for (document in documents) {
        val target = config.apifoxModuleTargets[document.moduleName]
            ?: config.apifoxModuleTargets[document.moduleName.removeSuffix("-api")]
        if (target == null) {
            System.err.println("Apifox upload skipped for ${document.moduleName}: missing --apifox-module ${document.moduleName}=...")
            continue
        }
        
        val targetProjectId = target.projectId ?: defaultProjectId
        if (targetProjectId.isNullOrBlank()) {
            System.err.println("Apifox upload skipped for ${document.moduleName}: projectId is not specified for module and no global --apifox-project provided.")
            continue
        }
        
        try {
            if (!config.apifoxUrlPrefix.isNullOrBlank()) {
                // URL mode: Apifox fetches the JSON from the URL
                val fileName = document.file?.name
                if (fileName == null) {
                    System.err.println("Apifox upload skipped for ${document.moduleName}: no output file (use -o to specify output path)")
                    continue
                }
                val openApiUrl = config.apifoxUrlPrefix.trimEnd('/') + "/" + fileName
                System.err.println("Apifox importing ${document.moduleName} from $openApiUrl")
                val result = client.importOpenApiByUrl(document.moduleName, targetProjectId, target.moduleId, openApiUrl)
                if (result.counters.endpointChanged == 0 && result.counters.endpointFailed == 0) {
                    System.err.println(
                        "Apifox imported ${result.moduleName} from URL, project=$targetProjectId, module=${result.moduleId ?: "root"}, " +
                            "status=${result.status}, but no endpoint changed. counters: ${result.counters}"
                    )
                } else {
                    System.err.println(
                        "Apifox imported ${result.moduleName} from URL, project=$targetProjectId, module=${result.moduleId ?: "root"}, " +
                            "status=${result.status}, counters: ${result.counters}"
                    )
                }
            } else {
                System.err.println("Apifox upload skipped for ${document.moduleName}: --apifox-url-prefix is required")
            }
        } catch (e: Exception) {
            System.err.println("Apifox upload failed for ${document.moduleName}: ${e.message}")
        }
    }
}

private fun writeIncrementalDocument(
    moduleName: String,
    title: String,
    fullOpenApiJson: String,
    outputFile: File
): GeneratedOpenApiDocument? {
    if (!outputFile.exists()) {
        outputFile.writeText(fullOpenApiJson)
        System.err.println("Documentation written to: ${outputFile.absolutePath}")
        return GeneratedOpenApiDocument(moduleName, title, fullOpenApiJson, outputFile)
    }

    val deltaJson = buildIncrementalOpenApiJson(outputFile, fullOpenApiJson)
    if (deltaJson == null) {
        System.err.println("No API changes for $moduleName, skipped incremental output.")
        return null
    }

    val incrementalFile = newSuffixFile(outputFile)
    incrementalFile.writeText(deltaJson)
    System.err.println("Incremental documentation written to: ${incrementalFile.absolutePath}")
    outputFile.writeText(fullOpenApiJson)
    System.err.println("Baseline documentation updated: ${outputFile.absolutePath}")
    return GeneratedOpenApiDocument(moduleName, title, deltaJson, incrementalFile)
}

internal fun buildIncrementalOpenApiJson(existingFile: File, fullOpenApiJson: String): String? {
    val gson = Gson()
    val oldRoot = try {
        JsonParser.parseString(existingFile.readText()).asJsonObject
    } catch (e: Exception) {
        System.err.println("Existing JSON ${existingFile.absolutePath} cannot be parsed, generating full incremental document.")
        return fullOpenApiJson
    }
    val newRoot = JsonParser.parseString(fullOpenApiJson).asJsonObject

    val oldPaths = oldRoot.getAsJsonObject("paths") ?: JsonObject()
    val newPaths = newRoot.getAsJsonObject("paths") ?: JsonObject()
    val oldSchemas = oldRoot
        .getAsJsonObject("components")
        ?.getAsJsonObject("schemas") ?: JsonObject()
    val newSchemas = newRoot
        .getAsJsonObject("components")
        ?.getAsJsonObject("schemas") ?: JsonObject()
    val changedSchemaNames = changedSchemaNames(oldSchemas, newSchemas)
    val selectedSchemaNames = linkedSetOf<String>()
    val changedPaths = JsonObject()

    for ((path, newPathElement) in newPaths.entrySet()) {
        val newPathObject = newPathElement.asJsonObject
        val oldPathObject = oldPaths.getAsJsonObject(path)
        val changedPathObject = JsonObject()
        for ((method, newOperation) in newPathObject.entrySet()) {
            if (method !in HTTP_METHOD_NAMES) continue
            val operationSchemaNames = expandSchemaRefs(collectSchemaRefs(newOperation), newSchemas)
            val oldOperation = oldPathObject?.get(method)
            val operationChanged = oldOperation == null || oldOperation != newOperation
            val referencedSchemaChanged = operationSchemaNames.any { it in changedSchemaNames }
            if (operationChanged || referencedSchemaChanged) {
                changedPathObject.add(method, newOperation)
                selectedSchemaNames.addAll(operationSchemaNames)
            }
        }
        if (changedPathObject.size() > 0) {
            changedPaths.add(path, changedPathObject)
        }
    }

    val selectedSchemas = buildSelectedSchemas(newSchemas, selectedSchemaNames)

    if (changedPaths.size() == 0 && selectedSchemas.size() == 0) return null

    val deltaRoot = JsonObject()
    deltaRoot.addProperty("openapi", newRoot.get("openapi")?.asString ?: "3.1.0")
    newRoot.getAsJsonObject("info")?.let { deltaRoot.add("info", it) }
    newRoot.getAsJsonArray("servers")?.let { deltaRoot.add("servers", it) }
    if (changedPaths.size() > 0) {
        deltaRoot.add("paths", changedPaths)
    }
    val components = JsonObject()
    if (selectedSchemas.size() > 0) {
        components.add("schemas", selectedSchemas)
    }
    newRoot.getAsJsonObject("components")
        ?.getAsJsonObject("securitySchemes")
        ?.let { components.add("securitySchemes", it) }
    if (components.size() > 0) {
        deltaRoot.add("components", components)
    }
    return gson.toJson(deltaRoot)
}

private fun changedSchemaNames(oldSchemas: JsonObject, newSchemas: JsonObject): Set<String> {
    val changed = linkedSetOf<String>()
    for ((name, newSchema) in newSchemas.entrySet()) {
        val oldSchema = oldSchemas.get(name)
        if (oldSchema == null || oldSchema != newSchema) {
            changed.add(name)
        }
    }
    return changed
}

private fun buildSelectedSchemas(newSchemas: JsonObject, selectedSchemaNames: Set<String>): JsonObject {
    val selectedSchemas = JsonObject()
    for (name in selectedSchemaNames) {
        newSchemas.get(name)?.let { selectedSchemas.add(name, it.deepCopy()) }
    }
    return selectedSchemas
}

private fun expandSchemaRefs(schemaNames: Set<String>, schemas: JsonObject): Set<String> {
    val expanded = linkedSetOf<String>()
    fun visit(name: String) {
        if (!expanded.add(name)) return
        val schema = schemas.get(name) ?: return
        for (nestedName in collectSchemaRefs(schema)) {
            visit(nestedName)
        }
    }
    for (name in schemaNames) {
        visit(name)
    }
    return expanded
}

private fun collectSchemaRefs(element: JsonElement?): Set<String> {
    val refs = linkedSetOf<String>()
    fun visit(current: JsonElement?) {
        if (current == null || current.isJsonNull) return
        when {
            current.isJsonObject -> {
                val currentObject = current.asJsonObject
                currentObject.get("\$ref")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.let { schemaNameFromRef(it) }
                    ?.let { refs.add(it) }
                for ((_, value) in currentObject.entrySet()) {
                    visit(value)
                }
            }
            current.isJsonArray -> {
                for (item in current.asJsonArray) {
                    visit(item)
                }
            }
        }
    }
    visit(element)
    return refs
}

private fun schemaNameFromRef(ref: String): String? {
    val prefix = "#/components/schemas/"
    if (!ref.startsWith(prefix)) return null
    return ref.removePrefix(prefix)
        .replace("~1", "/")
        .replace("~0", "~")
        .takeIf { it.isNotBlank() }
}

private fun newSuffixFile(outputFile: File): File {
    val parent = outputFile.parentFile ?: File(".")
    val extension = outputFile.extension
    val nameWithoutExtension = outputFile.nameWithoutExtension.removeSuffix("-new")
    val fileName = if (extension.isBlank()) {
        "$nameWithoutExtension-new"
    } else {
        "$nameWithoutExtension-new.$extension"
    }
    return File(parent, fileName)
}

private data class GeneratedOpenApiDocument(
    val moduleName: String,
    val title: String,
    val openApiJson: String,
    val file: File?
)

private val HTTP_METHOD_NAMES = setOf("get", "post", "put", "delete", "patch", "head", "options")
