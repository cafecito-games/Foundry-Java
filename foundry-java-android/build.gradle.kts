import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "games.cafecito.foundry.android"
    compileSdk = 36
    ndkVersion = "29.0.14206865"
    buildToolsVersion =
        libs.versions.android.build.tools
            .get()

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "games.cafecito.foundry.java.FoundryJavaInstrumentation"
        consumerProguardFiles("src/main/consumer-rules.pro")
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror")
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            externalNativeBuild {
                cmake {
                    arguments += "-DFOUNDRY_JAVA_BUILD_ANDROID_TEST_HOST=ON"
                }
            }
        }
        getByName("release") {
            externalNativeBuild {
                cmake {
                    arguments += "-DFOUNDRY_JAVA_BUILD_ANDROID_TEST_HOST=OFF"
                }
            }
        }
    }

    testOptions {
        targetSdk = 36
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {
    api(project(":foundry-java-runtime"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
            }
        }
    }
}

val nativeTestScript = rootProject.layout.projectDirectory.file("gradle/run-native-tests.sh")
val nativeAbiLayoutScript = rootProject.layout.projectDirectory.file("gradle/verify-native-abi-layout.sh")

val nativeAbiLayoutTest =
    tasks.register<Exec>("nativeAbiLayoutTest") {
        group = "verification"
        description = "Verifies deterministic native-only ABI layout generation."
        inputs.files(
            fileTree("src/main/cpp/cmake"),
            file("src/main/cpp/foundry_java_abi_layout.h.in"),
            rootProject.file("api/current/extension_api.json"),
            rootProject.file("api/current/provenance.json"),
        )
        inputs.file(nativeAbiLayoutScript)
        commandLine("bash", nativeAbiLayoutScript.asFile.absolutePath, projectDir.absolutePath)
    }

val nativeHostTest =
    tasks.register<Exec>("nativeHostTest") {
        group = "verification"
        description = "Builds and runs the JNI-free native lifecycle tests."
        inputs.files(
            fileTree("src/main/cpp"),
            fileTree("src/test/cpp"),
            fileTree("src/androidTest/cpp"),
            rootProject.fileTree("api/current"),
        )
        inputs.file(nativeTestScript)
        outputs.dir(layout.buildDirectory.dir("native-host"))
        commandLine("bash", nativeTestScript.asFile.absolutePath, "host", projectDir.absolutePath)
    }

val nativeSanitizerTest =
    tasks.register<Exec>("nativeSanitizerTest") {
        group = "verification"
        description = "Runs the native lifecycle tests under ASan and UBSan."
        inputs.files(
            fileTree("src/main/cpp"),
            fileTree("src/test/cpp"),
            fileTree("src/androidTest/cpp"),
            rootProject.fileTree("api/current"),
        )
        inputs.file(nativeTestScript)
        outputs.dir(layout.buildDirectory.dir("native-host-sanitized"))
        commandLine("bash", nativeTestScript.asFile.absolutePath, "sanitizer", projectDir.absolutePath)
    }

tasks.named("check") {
    dependsOn(nativeAbiLayoutTest, nativeHostTest, nativeSanitizerTest)
}
