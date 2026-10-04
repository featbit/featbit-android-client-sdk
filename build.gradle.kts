plugins {
    id("com.diffplug.spotless") version "7.0.2"
    id("com.android.library") version "8.5.2" apply false
    kotlin("android") version "1.9.25" apply false
    id("org.jetbrains.dokka") version "1.9.20" apply false
}

spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    kotlin {
        target("sdk/src/**/*.kt", "samples/*/src/**/*.kt", "consumer-tests/*/src/**/*.kt")
        ktfmt("0.54").kotlinlangStyle()
    }
    kotlinGradle {
        target(
            "*.gradle.kts",
            "sdk/*.gradle.kts",
            "samples/*.gradle.kts",
            "samples/*/*.gradle.kts",
            "consumer-tests/*.gradle.kts",
            "consumer-tests/*/*.gradle.kts",
        )
        ktfmt("0.54").kotlinlangStyle()
    }
    java {
        target(
            "sdk/src/**/*.java",
            "samples/*/src/**/*.java",
            "consumer-tests/*/src/**/*.java",
            "consumer-tests/shared/**/*.java",
        )
        googleJavaFormat("1.24.0").aosp()
    }
}
