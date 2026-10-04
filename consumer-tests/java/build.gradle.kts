plugins { id("com.android.application") }

android {
    namespace = "co.featbit.consumer.java"
    compileSdk = 34
    buildFeatures { buildConfig = true }
    defaultConfig {
        buildConfigField(
            "String",
            "EXPECTED_SDK_VERSION",
            "\"${providers.gradleProperty("sdkVersion").getOrElse("0.1.0-SNAPSHOT")}\"",
        )
    }
    sourceSets.getByName("main").java.srcDir("../shared")
    defaultConfig {
        applicationId = "co.featbit.consumer.java"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    implementation(
        "co.featbit:featbit-client-android:${providers.gradleProperty("sdkVersion").getOrElse("0.1.0-SNAPSHOT")}"
    )
    testImplementation("junit:junit:4.13.2")
}
