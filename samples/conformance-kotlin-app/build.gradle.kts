// AGP 9 compiles Kotlin itself and rejects org.jetbrains.kotlin.android, so a consumer application
// no longer applies a Kotlin plugin at all. This sample is the consumer-facing proof of that.
plugins {
    id("com.android.application")
    id("games.cafecito.foundry.java")
}

val publishedFoundryVersion: String =
    providers.gradleProperty("foundryVersion").getOrElse("0.1.0")

android {
    namespace = "games.cafecito.foundry.samples.kotlin.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "games.cafecito.foundry.samples.kotlin.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // The same Kotlin conformance matrix the library module runs on the JVM, executed on device.
        // These are Kotlin sources and must be added to the Kotlin source set: under AGP 9's
        // built-in Kotlin, java.srcDir no longer picks up .kt files and contributes nothing, which
        // builds an instrumentation APK that declares no tests rather than failing.
        named("androidTest") {
            kotlin.directories.add("../conformance-kotlin/src/conformance/kotlin")
        }
    }

    buildTypes {
        named("release") { isMinifyEnabled = false }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":conformance-kotlin"))
    implementation("games.cafecito.foundry:foundry-java-android:$publishedFoundryVersion")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

foundryJava {
    requestedAbis.set(setOf("x86_64"))
}
