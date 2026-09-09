// Root build.gradle.kts
plugins {
    // Versionen zentral deklariert, in den Submodulen per `apply` bzw. `id(...)` ohne Version referenziert.
    id("org.jetbrains.kotlin.jvm") version "1.9.24" apply false
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
