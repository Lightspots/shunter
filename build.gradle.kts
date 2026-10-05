import com.diffplug.gradle.spotless.SpotlessExtension
import org.jetbrains.compose.reload.gradle.withKotlinPlugin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.spotless)
}

subprojects {
    plugins.withType<JavaPlugin>().configureEach {
        configure<JavaPluginExtension> {
            targetCompatibility = JavaVersion.VERSION_25
            sourceCompatibility = JavaVersion.VERSION_25
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget = JvmTarget.JVM_25
            }
        }
    }
}

// Formatting with ktlint, its settings are in .editorconfig. spotlessCheck is part of `check`, so unformatted code
// fails the build; `./gradlew spotlessApply` fixes it.
val ktlintVersion = libs.versions.ktlint.get()
// Spotless reads .editorconfig while configuring; reading it here too makes it an input of the configuration cache,
// otherwise changes to it are ignored until the cache is dropped for another reason
providers.fileContents(layout.projectDirectory.file(".editorconfig")).asText.get()

allprojects {
    group = "ch.lightspots.shunter"
    version = "0.1.0-SNAPSHOT"

    apply(plugin = "com.diffplug.spotless")

    configure<SpotlessExtension> {
        kotlin {
            // Only our sources: the default (all source sets) would also pick up the generated BuildInfo
            target("src/**/*.kt")
            ktlint(ktlintVersion)
        }
        kotlinGradle {
            ktlint(ktlintVersion)
        }
    }
}
