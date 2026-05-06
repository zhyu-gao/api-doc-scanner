package com.itangcent.easyapi.scanner.jar

import java.io.ByteArrayInputStream
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class ClassBytes(
    val fullyQualifiedName: String,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClassBytes) return false
        return fullyQualifiedName == other.fullyQualifiedName
    }
    override fun hashCode(): Int = fullyQualifiedName.hashCode()
}

interface JarClassProvider {
    fun provideClasses(): Sequence<ClassBytes>
}

class SimpleJarScanner(private val jarPath: String) : JarClassProvider {
    override fun provideClasses(): Sequence<ClassBytes> = sequence {
        JarFile(jarPath).use { jar ->
            for (entry in jar.entries()) {
                if (entry.name.endsWith(".class") && !entry.name.contains('$')) {
                    val bytes = jar.getInputStream(entry).readBytes()
                    val fqn = entry.name.removeSuffix(".class").replace('/', '.')
                    yield(ClassBytes(fqn, bytes))
                }
            }
        }
    }
}

class SpringBootJarScanner(
    private val jarPath: String,
    private val scanLibs: Boolean = false
) : JarClassProvider {

    override fun provideClasses(): Sequence<ClassBytes> = sequence {
        ZipFile(jarPath).use { zip ->
            val entries = zip.entries().toList()
            for (entry in entries) {
                if (entry.name.startsWith("BOOT-INF/classes/") &&
                    entry.name.endsWith(".class") &&
                    !entry.name.contains('$')
                ) {
                    val bytes = zip.getInputStream(entry).readBytes()
                    val fqn = entry.name
                        .removePrefix("BOOT-INF/classes/")
                        .removeSuffix(".class")
                        .replace('/', '.')
                    yield(ClassBytes(fqn, bytes))
                }
            }
            if (scanLibs) {
                for (entry in entries) {
                    if (entry.name.startsWith("BOOT-INF/lib/") && entry.name.endsWith(".jar")) {
                        val nestedBytes = zip.getInputStream(entry).readBytes()
                        yieldAll(scanNestedJar(nestedBytes))
                    }
                }
            }
        }
    }

    private fun scanNestedJar(bytes: ByteArray): Sequence<ClassBytes> = sequence {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".class") && !entry.name.contains('$')) {
                    val classBytes = zis.readBytes()
                    val fqn = entry.name.removeSuffix(".class").replace('/', '.')
                    yield(ClassBytes(fqn, classBytes))
                }
                entry = zis.nextEntry
            }
        }
    }
}

fun createJarClassProvider(jarPath: String, scanLibs: Boolean = false): JarClassProvider {
    return if (isSpringBootJar(jarPath)) {
        SpringBootJarScanner(jarPath, scanLibs)
    } else {
        SimpleJarScanner(jarPath)
    }
}

private fun isSpringBootJar(jarPath: String): Boolean {
    JarFile(jarPath).use { jar ->
        return jar.entries().asSequence().any {
            it.name.startsWith("BOOT-INF/classes/") || it.name.startsWith("BOOT-INF/lib/")
        }
    }
}
