plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

group = "co.featbit"
version = "0.1.0-SNAPSHOT"

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
    kotlinOptions { jvmTarget = "11"; freeCompilerArgs += "-Xexplicit-api=strict" }
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
}

dependencies {
    // Only dependencies needed by this phase are shipped. Transport/platform dependencies
    // are pinned and resolved separately below, then added with their implementation phase.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("junit:junit:4.13.2")
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

afterEvaluate {
    // Resolve Android variants, not multiplatform desktop defaults.
    val runtimeAttributes = configurations.getByName("releaseRuntimeClasspath").attributes
    runtimeAttributes.keySet().forEach { key ->
        @Suppress("UNCHECKED_CAST")
        val typedKey = key as org.gradle.api.attributes.Attribute<Any>
        candidateRuntime.attributes.attribute(typedKey, runtimeAttributes.getAttribute(typedKey)!!)
    }
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "featbit-client-android"
                pom {
                    name.set("FeatBit Android Client SDK")
                    description.set("Phase 1 API and model foundation; not a functional SDK runtime.")
                    url.set("https://github.com/featbit/featbit-android-client-sdk")
                    licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT") } }
                    scm { url.set("https://github.com/featbit/featbit-android-client-sdk") }
                }
            }
        }
        repositories { maven { name = "localTest"; url = uri(rootProject.layout.buildDirectory.dir("test-repository")) } }
    }
}
