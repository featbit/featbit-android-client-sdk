pluginManagement {
    val consumerKotlinVersion =
        providers.gradleProperty("consumerKotlinVersion").getOrElse("1.9.25")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version
            providers.gradleProperty("consumerAgpVersion").getOrElse("8.5.2")
        id("org.jetbrains.kotlin.android") version consumerKotlinVersion
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

rootProject.name = "featbit-consumer-tests"

val language = providers.gradleProperty("consumerLanguage").orNull

require(language == null || language in setOf("java", "kotlin"))

if (language == null) include(":java", ":kotlin") else include(":$language")
