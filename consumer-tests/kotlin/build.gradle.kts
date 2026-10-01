plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "co.featbit.consumer.kotlin"
    compileSdk = 34
    defaultConfig { applicationId = "co.featbit.consumer.kotlin"; minSdk = 21; targetSdk = 34; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
    buildTypes { getByName("release") { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
}
dependencies {
    implementation("co.featbit:featbit-client-android:0.1.0-SNAPSHOT")
    testImplementation("junit:junit:4.13.2")
}
