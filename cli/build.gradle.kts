import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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

dependencies {
    implementation(project(":core"))
    implementation(libs.clikt)
    runtimeOnly(libs.slf4j.nop)
}

application {
    applicationName = "shunter"
    mainClass = "ch.lightspots.shunter.cli.MainKt"
    // Clikt's terminal detection uses JNA, which newer JDKs warn about without this
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    // Run from the directory gradle was started in, so relative paths behave as expected
    workingDir = project.rootDir
    standardInput = System.`in`
}
