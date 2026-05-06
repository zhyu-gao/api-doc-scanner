plugins {
    kotlin("jvm") version "2.3.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "com.itangcent"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.ow2.asm:asm:9.7")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.github.javaparser:javaparser-core:3.26.4")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

application {
    mainClass.set("com.itangcent.easyapi.scanner.ScannerMainKt")
}

tasks.shadowJar {
    archiveBaseName.set("easy-api-scanner")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.itangcent.easyapi.scanner.ScannerMainKt"
    }
}
