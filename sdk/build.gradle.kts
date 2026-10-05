import java.nio.file.Files

plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
    signing
    id("org.jetbrains.dokka")
}

group = "co.featbit"

version = providers.gradleProperty("sdkVersion").getOrElse("0.1.0-SNAPSHOT")

android {
    namespace = "co.featbit.android"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += "-Xexplicit-api=strict"
    }
    sourceSets {
        getByName("main").java.srcDir("src/main/kotlin")
        getByName("test").java.srcDir("src/test/kotlin")
    }
    publishing { singleVariant("release") { withSourcesJar() } }
    lint {
        abortOnError = true
        warningsAsErrors = true
        // Versions are deliberately pinned for Kotlin 1.9/minSdk 21, not auto-upgraded.
        disable += "GradleDependency"
    }
    testOptions.unitTests.all {
        if (!providers.gradleProperty("liveIntegration").isPresent) {
            it.exclude("**/Live*IntegrationTest*")
        } else {
            // Service state is not a Gradle input: a previous success is never live evidence.
            it.outputs.upToDateWhen { false }
            it.outputs.doNotCacheIf("Live integration requires current service responses") { true }
            val payload =
                file(
                    providers
                        .gradleProperty("liveEventPayload")
                        .getOrElse(
                            layout.buildDirectory
                                .file("phase-5-target-payload.json")
                                .get()
                                .asFile
                                .absolutePath
                        )
                )
            it.systemProperty("featbit.liveEventPayload", payload.absolutePath)
            it.outputs.file(payload)
            it.doFirst {
                // One explicit output only; failed/filtered runs must not leave old evidence.
                Files.deleteIfExists(payload.toPath())
                payload.parentFile.mkdirs()
            }
        }
    }
}

dependencies {
    // Platform and transport dependencies stay out of the public API.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

val candidateRuntime by configurations.creating { isCanBeConsumed = false }

dependencies {
    candidateRuntime("org.jetbrains.kotlin:kotlin-stdlib:1.9.25")
    candidateRuntime("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    candidateRuntime("com.squareup.okhttp3:okhttp:4.12.0")
    candidateRuntime("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    candidateRuntime("androidx.lifecycle:lifecycle-process:2.8.7")
}

tasks.register("resolveCandidateRuntime") {
    doLast { candidateRuntime.resolve().sortedBy { it.name }.forEach { println(it.name) } }
}

val documentationJar by
    tasks.registering(Jar::class) {
        archiveClassifier.set("javadoc")
        from(tasks.named("dokkaHtml"))
    }

afterEvaluate {
    // Resolve Android variants, not multiplatform desktop defaults.
    val runtimeAttributes = configurations.getByName("releaseRuntimeClasspath").attributes
    runtimeAttributes.keySet().forEach { key ->
        @Suppress("UNCHECKED_CAST") val typedKey = key as org.gradle.api.attributes.Attribute<Any>
        candidateRuntime.attributes.attribute(typedKey, runtimeAttributes.getAttribute(typedKey)!!)
    }
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifact(documentationJar)
                artifactId = "featbit-client-android"
                pom {
                    name.set("FeatBit Android Client SDK")
                    description.set(
                        "FeatBit Android client with lifecycle-aware online synchronization, local evaluation, analytics, cache and anonymous identity persistence."
                    )
                    url.set("https://github.com/featbit/featbit-android-client-sdk")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    scm {
                        url.set("https://github.com/featbit/featbit-android-client-sdk")
                        connection.set("scm:git:https://github.com/featbit/featbit-android-client-sdk.git")
                        developerConnection.set("scm:git:ssh://git@github.com/featbit/featbit-android-client-sdk.git")
                    }
                    developers {
                        developer {
                            id.set("featbit")
                            name.set("FeatBit Contributors")
                            url.set("https://github.com/featbit")
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "localTest"
                url =
                    uri(
                        providers
                            .gradleProperty("testRepository")
                            .getOrElse(
                                rootProject.layout.buildDirectory
                                    .dir("test-repository")
                                    .get()
                                    .asFile
                                    .absolutePath
                            )
                    )
            }
        }
    }
    if (providers.gradleProperty("signPublication").orNull == "true") {
        signing {
            useInMemoryPgpKeys(
                providers.environmentVariable("SIGNING_KEY").get(),
                providers.environmentVariable("SIGNING_PASSWORD").get(),
            )
            sign(publishing.publications["release"])
        }
    }
}
