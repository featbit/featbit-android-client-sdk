plugins { id("com.android.application") }
android {
    namespace = "co.featbit.consumer.java"
    compileSdk = 34
    defaultConfig { applicationId = "co.featbit.consumer.java"; minSdk = 21; targetSdk = 34; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    buildTypes { getByName("release") { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
}
dependencies {
    implementation("co.featbit:featbit-client-android:0.1.0-SNAPSHOT")
    testImplementation("junit:junit:4.13.2")
}
