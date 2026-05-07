package com.itangcent.easyapi.scanner.source

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object MavenDependencyResolver {
    
    data class Dependency(val groupId: String, val artifactId: String, var version: String)
    
    fun resolveDependencyJars(pomFiles: List<File>): List<File> {
        val userHome = System.getProperty("user.home")
        val m2Repo = File(userHome, ".m2/repository")
        
        // Cache directory for downloaded jars
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "easy-api-scanner-m2")
        cacheDir.mkdirs()

        val jars = mutableSetOf<File>()
        val properties = mutableMapOf<String, String>()
        val repositories = mutableSetOf("https://repo1.maven.org/maven2/")
        val dependencies = mutableSetOf<Dependency>()

        // 1. Extract properties, repositories and dependencies
        for (pomFile in pomFiles) {
            if (!pomFile.isFile) continue
            val content = pomFile.readText()
            
            // Extract properties
            val propsMatch = Regex("<properties>(.*?)</properties>", RegexOption.DOT_MATCHES_ALL).find(content)
            if (propsMatch != null) {
                val propsContent = propsMatch.groupValues[1]
                val propRegex = Regex("<([^>]+)>([^<]+)</\\1>")
                for (match in propRegex.findAll(propsContent)) {
                    properties[match.groupValues[1].trim()] = match.groupValues[2].trim()
                }
            }
            
            // Extract repositories
            val reposMatch = Regex("<repositories>(.*?)</repositories>", RegexOption.DOT_MATCHES_ALL).find(content)
            if (reposMatch != null) {
                val urlRegex = Regex("<url>([^<]+)</url>")
                for (match in urlRegex.findAll(reposMatch.groupValues[1])) {
                    var url = match.groupValues[1].trim()
                    if (!url.endsWith("/")) url += "/"
                    repositories.add(url)
                }
            }
            
            // Extract dependencies
            val depRegex = Regex("<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>(?:\\s*<version>([^<]+)</version>)?", RegexOption.DOT_MATCHES_ALL)
            for (match in depRegex.findAll(content)) {
                val groupId = match.groupValues[1].trim()
                val artifactId = match.groupValues[2].trim()
                val version = match.groupValues[3].trim()
                
                // Only care about dependencies with versions (or properties)
                if (version.isNotEmpty()) {
                    dependencies.add(Dependency(groupId, artifactId, version))
                }
            }
        }

        // 2. Resolve versions
        for (dep in dependencies) {
            if (dep.version.startsWith("\${") && dep.version.endsWith("}")) {
                val propName = dep.version.substring(2, dep.version.length - 1)
                properties[propName]?.let { dep.version = it }
            }
        }

        // 3. Download or find Jars
        for (dep in dependencies) {
            if (dep.version.isEmpty() || dep.version.startsWith("\${")) continue
            
            val groupPath = dep.groupId.replace('.', '/')
            val artifactPath = "$groupPath/${dep.artifactId}/${dep.version}"
            
            // Check temp cache
            var jarFound = false
            val cacheM2Dir = File(cacheDir, artifactPath)
            if (cacheM2Dir.isDirectory) {
                val jarFile = cacheM2Dir.listFiles()?.find { it.name.endsWith(".jar") && !it.name.endsWith("-sources.jar") && !it.name.endsWith("-javadoc.jar") }
                if (jarFile != null) {
                    jars.add(jarFile)
                    jarFound = true
                }
            }
            
            if (jarFound) continue
            
            // Check local m2 as a fallback
            val localM2Dir = File(m2Repo, artifactPath)
            if (localM2Dir.isDirectory) {
                val jarFile = localM2Dir.listFiles()?.find { it.name.endsWith(".jar") && !it.name.endsWith("-sources.jar") && !it.name.endsWith("-javadoc.jar") }
                if (jarFile != null) {
                    jars.add(jarFile)
                    jarFound = true
                }
            }
            
            if (jarFound) continue
            
            // Download from repositories
            System.err.println("Attempting to download ${dep.groupId}:${dep.artifactId}:${dep.version} ...")
            cacheM2Dir.mkdirs()
            
            for (repo in repositories) {
                try {
                    val downloadedFile = downloadArtifact(repo, dep, cacheM2Dir)
                    if (downloadedFile != null) {
                        jars.add(downloadedFile)
                        System.err.println("Successfully downloaded ${dep.artifactId}-${dep.version}.jar from $repo")
                        break
                    }
                } catch (e: Exception) {
                    // Ignore and try next repo
                }
            }
        }
        
        return jars.toList()
    }
    
    private fun downloadArtifact(repoUrl: String, dep: Dependency, targetDir: File): File? {
        val groupPath = dep.groupId.replace('.', '/')
        
        if (dep.version.endsWith("-SNAPSHOT")) {
            // Need to parse maven-metadata.xml
            val metadataUrl = "${repoUrl}$groupPath/${dep.artifactId}/${dep.version}/maven-metadata.xml"
            val metadataContent = try {
                URL(metadataUrl).readText()
            } catch (e: Exception) {
                return null
            }
            
            val timestampMatch = Regex("<timestamp>([^<]+)</timestamp>").find(metadataContent)
            val buildNumberMatch = Regex("<buildNumber>([^<]+)</buildNumber>").find(metadataContent)
            
            if (timestampMatch != null && buildNumberMatch != null) {
                val timestamp = timestampMatch.groupValues[1]
                val buildNumber = buildNumberMatch.groupValues[1]
                val snapshotVersion = dep.version.replace("-SNAPSHOT", "-$timestamp-$buildNumber")
                
                val jarName = "${dep.artifactId}-$snapshotVersion.jar"
                val jarUrl = "${repoUrl}$groupPath/${dep.artifactId}/${dep.version}/$jarName"
                
                val targetFile = File(targetDir, "${dep.artifactId}-${dep.version}.jar")
                if (downloadFile(jarUrl, targetFile)) {
                    return targetFile
                }
            }
        } else {
            val jarName = "${dep.artifactId}-${dep.version}.jar"
            val jarUrl = "${repoUrl}$groupPath/${dep.artifactId}/${dep.version}/$jarName"
            val targetFile = File(targetDir, jarName)
            if (downloadFile(jarUrl, targetFile)) {
                return targetFile
            }
        }
        return null
    }
    
    private fun downloadFile(urlString: String, targetFile: File): Boolean {
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            
            if (connection.responseCode == 200) {
                connection.inputStream.use { input ->
                    Files.copy(input, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                return true
            }
        } catch (e: Exception) {
            // Ignore
        }
        return false
    }
}
