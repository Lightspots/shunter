import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

// BuildInfo.VERSION comes from the project version, so there is only one place to change it
val generateBuildInfo = tasks.register("generateBuildInfo") {
    description = "Generates BuildInfo.kt with the current version"
    val version = project.version.toString()
    val outputDir = layout.buildDirectory.dir("generated/buildInfo/kotlin")
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("ch/lightspots/shunter/core/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package ch.lightspots.shunter.core
            |
            |object BuildInfo {
            |    const val VERSION = "$version"
            |}
            |
            """.trimMargin(),
        )
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateBuildInfo)
}

dependencies {
    // suspend functions are part of the public API
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.kotlin.logging)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
    // kotlin-logging announces itself on stdout otherwise
    systemProperty("kotlin-logging.logStartupMessage", "false")
}
