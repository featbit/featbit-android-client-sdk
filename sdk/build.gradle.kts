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
    testOptions.unitTests.all {
        if (!providers.gradleProperty("liveIntegration").isPresent) it.exclude("**/LiveSyncIntegrationTest*")
    }
}

dependencies {
    // Transport/codec dependencies are internal. Android lifecycle integration remains deferred.
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
                    description.set("FeatBit Android client with online synchronization, local evaluation, cache and anonymous identity persistence; analytics and Android lifecycle integration remain in development.")
                    url.set("https://github.com/featbit/featbit-android-client-sdk")
                    licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT") } }
                    scm { url.set("https://github.com/featbit/featbit-android-client-sdk") }
                }
            }
        }
        repositories { maven { name = "localTest"; url = uri(rootProject.layout.buildDirectory.dir("test-repository")) } }
    }
}
