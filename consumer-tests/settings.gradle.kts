pluginManagement {
    val consumerKotlinVersion = providers.gradleProperty("consumerKotlinVersion").getOrElse("1.9.25")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.application") version "8.5.2"
        id("org.jetbrains.kotlin.android") version consumerKotlinVersion
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("../build/test-repository") }
        google()
        mavenCentral()
    }
}
rootProject.name = "featbit-consumer-tests"
include(":java", ":kotlin")
