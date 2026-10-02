plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "co.featbit.consumer.kotlin"
    compileSdk = 34
    buildFeatures { buildConfig = true }
    defaultConfig { buildConfigField("String", "EXPECTED_SDK_VERSION", "\"${providers.gradleProperty("sdkVersion").getOrElse("0.1.0-SNAPSHOT")}\"") }
    sourceSets.getByName("main").java.srcDir("../shared")
    defaultConfig { applicationId = "co.featbit.consumer.kotlin"; minSdk = 21; targetSdk = 34; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
    buildTypes { getByName("release") { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
    // Explicitly opt in for device checks, including the non-debuggable R8 release fixture.
    if (providers.gradleProperty("phase6Probe").orNull == "true") {
        listOf("debug", "release").forEach { variant ->
            sourceSets.getByName(variant).manifest.srcFile("src/phase6/AndroidManifest.xml")
        }
    }
}
dependencies {
    implementation("co.featbit:featbit-client-android:${providers.gradleProperty("sdkVersion").getOrElse("0.1.0-SNAPSHOT")}")
    testImplementation("junit:junit:4.13.2")
}
