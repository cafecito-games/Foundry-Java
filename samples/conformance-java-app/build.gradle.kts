plugins {
    id("com.android.application")
    id("games.cafecito.foundry.java")
}

val publishedFoundryVersion: String =
    providers.gradleProperty("foundryVersion").getOrElse("0.1.0")

android {
    namespace = "games.cafecito.foundry.samples.java.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "games.cafecito.foundry.samples.java.app"
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
        // The same conformance matrix the library module runs on the JVM, executed on device.
        named("androidTest") {
            java.srcDir("../conformance-java/src/conformance/java")
        }
    }

    buildTypes {
        named("release") { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(project(":conformance-java"))
    implementation("games.cafecito.foundry:foundry-java-android:$publishedFoundryVersion")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

foundryJava {
    requestedAbis.set(setOf("x86_64"))
}
