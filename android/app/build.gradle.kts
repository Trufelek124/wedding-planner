plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The server the app talks to is fixed when the app is built:
//   ./gradlew assembleDebug -PserverUrl=https://planner.example
// The default is the address under which an emulator reaches the machine it runs on.
val serverUrl = providers.gradleProperty("serverUrl").orElse("http://10.0.2.2:8080")

android {
    namespace = "io.github.trufelek124.weddingplanner"
    compileSdk = 37

    defaultConfig {
        // The application id cannot change once the app is installed on a phone.
        applicationId = "io.github.trufelek124.weddingplanner"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SERVER_URL", "\"${serverUrl.get()}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Lets the JVM tests render screens with real resources (Robolectric).
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")

    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.18")
}
