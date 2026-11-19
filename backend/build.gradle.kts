import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.1.2"
}

group = "io.github.trufelek124"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.springframework.modulith:spring-modulith-bom:2.1.2"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Spring Security with JWT support: checks our own access tokens and Google's ID tokens.
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    // Describes the API (OpenAPI). The description is served only when API_DOCS_ENABLED is true.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.2.0")
    // Link previews: an HTTP client that lets us decide which addresses a host name may stand for,
    // and a parser for the fetched page. Neither runs scripts or fetches anything by itself.
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation("org.jsoup:jsoup:1.24.1")
    // The AI service for reading the mail (S04, D-007): its own Java SDK. Made only when a key is given.
    implementation("com.anthropic:anthropic-java:3.3.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Tests run against a real PostgreSQL 18 that the test run starts itself (no Docker needed).
    testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.7.0"))
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    // The library brings the binaries for Intel only; these let the tests run on ARM machines too.
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-darwin-arm64v8")
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-linux-arm64v8")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
    buildInfo()
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register<JavaExec>("devDatabase") {
    group = "application"
    description = "Starts a local PostgreSQL 18 for running the application (stop with Ctrl+C)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "io.github.trufelek124.weddingplanner.DevDatabase"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

tasks.register<Test>("updateApiContract") {
    group = "verification"
    description = "Rewrites api/openapi.json from the code. Regenerate the clients afterwards."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*ApiContractTest") }
    systemProperty("api.contract.update", "true")
    outputs.upToDateWhen { false }
}

// Measures the two AI steps of reading the mail over the invented sample set (S04 section 10, WP-TS-78).
// It calls the REAL AI service and a call costs money, so nothing depends on it: not check, test, build or the gate.
// Without APP_AI_API_KEY in the environment and -PspendMoney=yes it calls nothing and says what it needs.
//   APP_AI_API_KEY=... ./gradlew measureReading -PspendMoney=yes [-Ponly=pl,uk,en] [-Psteps=triage|both] [-Plimit=N]
//   ./gradlew measureReading -PdryRun=yes     (counts the calls and estimates the cost; calls nothing)
// The key is only read from the environment. The service's address is not an option here (tests set it by a system property).
tasks.register<JavaExec>("measureReading") {
    group = "reading"
    description = "Runs triage and extraction over the sample messages against the REAL AI service (costs money; needs a key and -PspendMoney=yes)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "io.github.trufelek124.weddingplanner.mailreading.internal.MeasureReading"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    outputs.upToDateWhen { false }
    val options = mapOf(
        "spendMoney" to "--spend-money",
        "dryRun" to "--dry-run",
        "only" to "--only",
        "steps" to "--steps",
        "limit" to "--limit",
    )
    options.forEach { (property, flag) ->
        (findProperty(property) as String?)?.let { args("$flag=$it") }
    }
}
