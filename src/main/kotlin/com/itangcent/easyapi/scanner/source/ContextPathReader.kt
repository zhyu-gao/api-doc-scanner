package com.itangcent.easyapi.scanner.source

import java.io.File

/**
 * 从 Spring Boot 配置文件中读取 server.servlet.context-path 或 server.context-path。
 */
object ContextPathReader {

    private val CONFIG_FILE_NAMES = listOf(
        "application.yml",
        "application.yaml",
        "application.properties",
        "bootstrap.yml",
        "bootstrap.yaml",
        "bootstrap.properties"
    )

    /**
     * 根据模块的 sourceRoot（src/main/java）查找对应的 resources 目录下的 context-path。
     */
    fun readContextPath(moduleSourceRoot: File): String {
        val resourcesDir = resolveResourcesDir(moduleSourceRoot) ?: return ""
        val configFiles = findConfigFiles(resourcesDir)
        for (file in configFiles) {
            val path = when {
                file.extension == "properties" -> readFromProperties(file)
                file.extension == "yml" || file.extension == "yaml" -> readFromYaml(file)
                else -> ""
            }
            if (path.isNotBlank()) return normalizePath(path)
        }
        return ""
    }

    private fun resolveResourcesDir(sourceRoot: File): File? {
        // sourceRoot is typically .../module/src/main/java
        val srcMain = sourceRoot.parentFile ?: return null
        val resourcesDir = File(srcMain, "resources")
        return if (resourcesDir.isDirectory) resourcesDir else null
    }

    private fun findConfigFiles(resourcesDir: File): List<File> {
        val files = mutableListOf<File>()
        // Direct config files
        for (name in CONFIG_FILE_NAMES) {
            val file = File(resourcesDir, name)
            if (file.isFile) files.add(file)
        }
        // Profile-specific files (application-*.yml etc.) — lower priority
        resourcesDir.listFiles()?.filter { file ->
            file.isFile && CONFIG_FILE_NAMES.any { configName ->
                val base = configName.substringBeforeLast('.')
                val ext = configName.substringAfterLast('.')
                file.name.startsWith("$base-") && file.name.endsWith(".$ext")
            }
        }?.let { files.addAll(it) }
        // config/ subdirectory
        val configSubdir = File(resourcesDir, "config")
        if (configSubdir.isDirectory) {
            for (name in CONFIG_FILE_NAMES) {
                val file = File(configSubdir, name)
                if (file.isFile) files.add(file)
            }
        }
        return files
    }

    private fun readFromProperties(file: File): String {
        for (line in file.readLines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || trimmed.startsWith("!")) continue
            val key = trimmed.substringBefore('=', "").trim()
            val value = trimmed.substringAfter('=', "").trim()
            if (key == "server.servlet.context-path" || key == "server.context-path") {
                return value
            }
        }
        return ""
    }

    private fun readFromYaml(file: File): String {
        val lines = file.readLines()
        // Try server.servlet.context-path first (more specific)
        val servletPath = findYamlValue(lines, listOf("server", "servlet", "context-path"))
        if (servletPath.isNotBlank()) return servletPath
        // Fall back to server.context-path
        return findYamlValue(lines, listOf("server", "context-path"))
    }

    private fun findYamlValue(lines: List<String>, keyPath: List<String>): String {
        // Simple YAML parser for flat/nested keys like:
        // server:
        //   servlet:
        //     context-path: /api
        // or: server.servlet.context-path: /api
        // or: server:
        //   context-path: /api

        // First try dotted key format: "server.servlet.context-path: /api"
        val dottedKey = keyPath.joinToString(".")
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("$dottedKey:") || trimmed.startsWith("$dottedKey :")) {
                return extractYamlScalar(trimmed.substringAfter(':'))
            }
        }

        // Try nested YAML structure
        var targetDepth = 0
        var matchedDepth = 0
        for (line in lines) {
            if (line.trim().startsWith("#") || line.isBlank()) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }
            val trimmed = line.trim()

            // Check if this line matches the current expected key
            if (targetDepth < keyPath.size) {
                val expectedKey = keyPath[targetDepth]
                if (trimmed.startsWith("$expectedKey:") || trimmed.startsWith("$expectedKey :")) {
                    targetDepth++
                    matchedDepth = indent
                    if (targetDepth == keyPath.size) {
                        // Found the key, extract value
                        val value = extractYamlScalar(trimmed.substringAfter(':'))
                        if (value.isNotBlank()) return value
                        // Value might be on next line (multi-line)
                        continue
                    }
                    continue
                }
            }

            // If we're inside a matched block and hit a line at same or lesser indent, we've exited
            if (targetDepth > 0 && targetDepth < keyPath.size && indent <= matchedDepth) {
                targetDepth = 0
            }
        }
        return ""
    }

    private fun extractYamlScalar(afterColon: String): String {
        val value = afterColon.trim()
        if (value.isBlank()) return ""
        // Remove quotes if present
        return value.removeSurrounding("\"").removeSurrounding("'")
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim().removeSurrounding("\"")
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }
}
