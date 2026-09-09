pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "serviceroboter-ki"

// 'core' ist reines Kotlin/JVM (keine Android-Abhängigkeit) -> lokal ohne Android SDK baubar & testbar.
include(":core")

// 'app' ist das Android-Modul mit dem eigentlichen Serviceroboter-Prototyp.
// Erfordert Android Studio + Android SDK zum Bauen (siehe README.md).
include(":app")
