plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.clikt)
    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)
}

application {
    applicationName = "shunter"
    mainClass = "ch.lightspots.shunter.cli.MainKt"
    applicationDefaultJvmArgs = listOf(
        // Clikt's terminal detection uses JNA, which newer JDKs warn about without this
        "--enable-native-access=ALL-UNNAMED",
        // kotlin-logging announces itself on stdout otherwise
        "-Dkotlin-logging.logStartupMessage=false",
    )
}

tasks.named<JavaExec>("run") {
    // Run from the directory gradle was started in, so relative paths behave as expected
    workingDir = project.rootDir
    standardInput = System.`in`
}
