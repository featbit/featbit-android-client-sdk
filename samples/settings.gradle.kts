pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "1.9.25"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    url =
                        uri(
                            providers
                                .gradleProperty("testRepository")
                                .getOrElse("../build/test-repository")
                        )
                }
            }
            filter { includeGroup("co.featbit") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "featbit-samples"

include(":kotlin", ":java")
