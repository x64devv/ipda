import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("com.google.protobuf") version "0.9.4"
    application
}

group = "ipda"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // cTrader Open API wire protocol (proto2, vendored from spotware/openapi-proto-messages @ 3fd8bdd)
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    // Snapshot store
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
}

application {
    mainClass.set("ipda.MainKt")
}

/**
 * Second launcher in the distribution: bin/ipda-live runs the LIVE demo loop.
 * `./gradlew installDist` (or the CI-built tarball) then contains everything a
 * server needs — no Gradle on the box, just a JRE 21.
 */
val liveStartScripts = tasks.register<CreateStartScripts>("liveStartScripts") {
    mainClass.set("ipda.live.LiveMainKt")
    applicationName = "ipda-live"
    outputDir = layout.buildDirectory.dir("live-scripts").get().asFile
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
}

distributions {
    main {
        contents {
            from(liveStartScripts) {
                into("bin")
                filePermissions { unix("rwxr-xr-x") }
            }
        }
    }
}

/** Run the history fetcher: ./gradlew fetch --args="--days 730" */
tasks.register<JavaExec>("fetch") {
    group = "application"
    description = "Fetch trendbar history from the cTrader Open API demo feed into a snapshot"
    mainClass.set("ipda.fetch.FetchMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

/** Generate a SYNTHETIC snapshot for pipeline dry-runs: ./gradlew synth --args="--days 180" */
tasks.register<JavaExec>("synth") {
    group = "application"
    description = "Write a deterministic synthetic snapshot (pipeline dry-run only)"
    mainClass.set("ipda.tools.SynthMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

/** Run the LIVE demo loop (local machine only — needs demo.ctraderapi.com): ./gradlew live */
tasks.register<JavaExec>("live") {
    group = "application"
    description = "Run v1 live against the cTrader demo account (feed + broker adapters over the same seams)"
    mainClass.set("ipda.live.LiveMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

/** Run the H1-only baseline backtest: ./gradlew backtest --args="--snapshot snap-xxx" */
tasks.register<JavaExec>("backtest") {
    group = "application"
    description = "Replay a snapshot through the engine with the v0 baseline strategy"
    mainClass.set("ipda.backtest.BacktestMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
