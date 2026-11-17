// Kotlin support is built into the Android Gradle plugin (no kotlin-android plugin).
// The line below makes it use this Kotlin version; the Compose compiler plugin must carry the same one.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("org.openapi.generator") version "7.14.0" apply false
}
