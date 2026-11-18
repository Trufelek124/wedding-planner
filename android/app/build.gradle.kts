import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.openapi.generator")
    id("com.google.devtools.ksp")
}

// The server the app talks to is fixed when the app is built:
//   ./gradlew assembleDebug -PserverUrl=https://planner.example
// The default is the address under which an emulator reaches the machine it runs on.
val serverUrl = providers.gradleProperty("serverUrl").orElse("http://10.0.2.2:8080")

// The API's data classes are generated from the backend's API description at every build
// and are never committed or edited: change the backend, then ./gradlew updateApiContract there.
val apiModels = layout.buildDirectory.dir("generated/api-models")

openApiGenerate {
    generatorName = "kotlin"
    this.library.set("multiplatform")
    // Start from an empty directory, so a model the API no longer has does not linger.
    cleanupOutput = true
    inputSpec = rootProject.file("../backend/api/openapi.json").path
    outputDir = apiModels.map { it.asFile.path }
    modelPackage = "io.github.trufelek124.weddingplanner.api.model"
    globalProperties = mapOf("models" to "", "modelDocs" to "false", "modelTests" to "false")
    configOptions = mapOf(
        "sourceFolder" to "src/main/kotlin",
        "enumPropertyNaming" to "UPPERCASE",
        "dateLibrary" to "kotlinx-datetime",
    )
}

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
            // The tests that take a picture of a screen write it under build/outputs/roborazzi, to be looked at.
            // They record, and never compare with a stored picture: no picture is kept in the repository.
            all { it.systemProperty("roborazzi.test.record", "true") }
        }
    }
}

// The database's layout is written out at every build and committed (app/schemas): a later version
// of the app needs it to carry over what an earlier one stored.
ksp {
    arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.path)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")

    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Talking to the server (D-005).
    implementation("io.ktor:ktor-client-okhttp:3.6.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
    // The session: what is kept on the phone, and signing in with Google.
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // What was loaded, kept for when there is no connection; and moving between the screens.
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    // Sending what was changed without a connection once there is one, also when the app is closed by then.
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation("io.ktor:ktor-client-mock:3.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("androidx.work:work-testing:2.12.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.79.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.79.0")
}

// Hands the generated models to the Android build as a generated source directory, so that
// compilation, lint and the IDE all see them and every task that needs them runs the generator first.
abstract class ApiModelSources : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generated: DirectoryProperty

    @get:OutputDirectory
    abstract val sources: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(generated)
            into(sources)
        }
    }
}

val apiModelSources = tasks.register<ApiModelSources>("apiModelSources") {
    dependsOn(tasks.named("openApiGenerate"))
    generated = apiModels.map { it.dir("src/main/kotlin") }
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(apiModelSources, ApiModelSources::sources)
    }
}
