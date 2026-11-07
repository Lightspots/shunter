import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":logging"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlin.logging)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // kotlin-logging announces itself on stdout otherwise
    systemProperty("kotlin-logging.logStartupMessage", "false")
}

compose.desktop {
    application {
        mainClass = "ch.lightspots.shunter.app.MainKt"
        jvmArgs += listOf(
            // kotlin-logging announces itself on stdout otherwise
            "-Dkotlin-logging.logStartupMessage=false",
            // Skiko loads a native library, which newer JDKs warn about without this
            "--enable-native-access=ALL-UNNAMED",
        )
        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "shunter"
            // Native packages need a plain x.y.z version
            packageVersion = project.version.toString().substringBefore('-')
            description = "Mod manager for Transport Fever 3 on Linux"
            vendor = "Lightspots"
            licenseFile.set(rootProject.file("LICENSE"))
            linux {
                menuGroup = "Game"
            }
        }
    }
}
