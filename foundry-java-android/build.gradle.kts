plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "games.cafecito.foundry.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {
    api(project(":foundry-java-runtime"))
}
