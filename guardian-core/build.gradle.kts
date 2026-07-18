plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

// The guardian's pure core: rules, guards, baseline, grounding, policy, and the
// text-surface logic — everything deterministic and Android-free. The compiler
// enforces the boundary: this module cannot see the Android SDK, so nothing in
// it can touch a platform API, and all of it runs as plain JVM tests.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    testFixturesImplementation(libs.kotlinx.coroutines.core)
}
