plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Logback setup shared by the frontends (cli, app); core stays on the kotlin-logging facade only
dependencies {
    implementation(project(":core"))
    implementation(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.kotlin.logging)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // kotlin-logging announces itself on stdout otherwise
    systemProperty("kotlin-logging.logStartupMessage", "false")
}
