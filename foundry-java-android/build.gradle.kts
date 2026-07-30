import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.tasks.Jar
import org.gradle.process.CommandLineArgumentProvider

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
        // Sources are part of the published contract for every module, and the Android library
        // plugin can only declare them here. Javadoc is built below instead of with
        // withJavadocJar(); see releaseJavadoc. Both archives are required by
        // gradle/release-topology.txt.
        singleVariant("release") {
            withSourcesJar()
        }
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

// The Android library plugin builds its Javadoc with a bundled Dokka that cannot read Java records
// in a dependency and fails with "Record requires ASM8"; foundry-java-runtime publishes records. The
// published Javadoc archive is therefore produced with the JDK javadoc tool over this module's own
// Java sources, so the release still ships Javadoc for every module.
val releaseJavadoc =
    tasks.register<Javadoc>("releaseJavadoc") {
        group = "documentation"
        description = "Builds the published Javadoc for the release variant."
        source = fileTree("src/main/java") { include("**/*.java") }
        classpath = files(configurations.named("releaseCompileClasspath"), android.bootClasspath)
        setDestinationDir(
            layout.buildDirectory
                .dir("docs/releaseJavadoc")
                .get()
                .asFile,
        )
        (options as StandardJavadocDocletOptions).apply {
            // Javadoc stamps its generation date into every page, which would make the archive
            // differ between two builds of the same commit.
            addBooleanOption("notimestamp", true)
            addStringOption("Xdoclint:none", "-quiet")
        }
    }

val releaseJavadocJar =
    tasks.register<Jar>("releaseJavadocJar") {
        group = "documentation"
        description = "Packages the published Javadoc for the release variant."
        archiveClassifier.set("javadoc")
        from(releaseJavadoc)
    }

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifact(releaseJavadocJar)
            }
        }
    }
}

val nativeTestScript = rootProject.layout.projectDirectory.file("gradle/run-native-tests.sh")
val nativeAbiLayoutScript = rootProject.layout.projectDirectory.file("gradle/verify-native-abi-layout.sh")

// src/main/cpp/CMakeLists.txt reads RUNTIME_CONTRACT_VERSION out of this file and configures it into
// foundry_java_contract.h, so the native tests' result depends on it. It sits outside every source
// tree they declare, and without it a change to the contract version could replay an entry built
// against the previous one — the tests would report success having compiled neither.
val runtimeContractSource =
    project(":foundry-java-runtime")
        .layout.projectDirectory
        .file("src/main/java/games/cafecito/foundry/runtime/FoundryRuntime.java")

// Exec puts `executable` and `args` into the build cache key but not `workingDir`, so every path
// these three tasks name is relative to the repository root. That is what makes an entry stored by
// one checkout replayable by another; gradle/verify-build-cache-portability.sh proves it.
fun rootRelative(path: File): String = path.relativeTo(rootDir).invariantSeparatorsPath

// These three shell out to cmake, ctest and a host C++ compiler, none of which Gradle can see as a
// declared input. Making them cacheable therefore requires naming the toolchain in the key: without
// it, an entry produced under one compiler would suppress the verification under another, and the
// check would go green having run nothing. The host platform alone is not sufficient — a compiler
// upgrade, a sanitizer behaviour change or a CXXFLAGS override all change what these tasks prove
// while leaving the platform identical.
//
// providers.exec is what keeps the version probes compatible with
// --configuration-cache-problems=fail, and both probes emit a stable line, so they do not churn the
// configuration cache between runs.
val hostPlatform = "${System.getProperty("os.name")}/${System.getProperty("os.arch")}"

fun firstLineOf(vararg command: String): Provider<String> =
    providers
        .exec {
            commandLine(*command)
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { output ->
            output
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .trim()
        }

// A probe that cannot be started must not fail the build. The compiler name itself is in the key
// either way, through the CXX entry below, so an unprobeable compiler degrades the key's precision
// rather than its correctness.
fun firstLineOrBlank(command: List<String>): String =
    runCatching { firstLineOf(*command.toTypedArray()).get() }.getOrDefault("")

val cmakeVersion = firstLineOf("cmake", "--version")

// The compiler is whichever one CMake selects, and these variables are what steer that choice, so
// their values belong in the key alongside the version strings: an entry built under a CXXFLAGS
// override must not stand in for a default build.
//
// Everything CMake itself reads from the environment is captured by prefix rather than by name.
// CMAKE_GENERATOR, CMAKE_TOOLCHAIN_FILE, CMAKE_PREFIX_PATH and their relatives all change what gets
// configured and built, and a hand-maintained list of them is a list that falls behind — the same
// reason the repository contract suites are excluded from replay rather than given an input list.
//
// Known limitation: the *values* are keyed, not the contents of any file or directory a value names.
// Editing a CMAKE_TOOLCHAIN_FILE in place would leave the key unchanged. Nothing in this repository or
// its CI sets a file-valued CMAKE_ variable, so this is not reachable here without a developer
// configuring one by hand; closing it in general means hashing arbitrary machine-specific paths pulled
// from the environment, which works against the path portability the rest of these keys establish.
// See issue #76.
val compilerVariables = listOf("CC", "CXX", "CFLAGS", "CXXFLAGS", "LDFLAGS")
val cmakeEnvironment = providers.environmentVariablesPrefixedBy("CMAKE_")

val nativeToolchain =
    providers.provider {
        // CMake accepts a CXX that carries required arguments after the compiler, so only the first
        // token is the executable. Handing the whole value to Gradle as one executable name would
        // stop the tasks from starting on a configuration CMake itself accepts.
        val compiler =
            providers
                .environmentVariable("CXX")
                .getOrElse("c++")
                .trim()
                .split(Regex("\\s+"))
                .filter(String::isNotEmpty)
                .ifEmpty { listOf("c++") }
        val probes =
            listOf(
                firstLineOrBlank(listOf("cmake", "--version")),
                firstLineOrBlank(compiler + "--version"),
            )
        val overrides =
            compilerVariables.map { name ->
                "$name=" + providers.environmentVariable(name).getOrElse("")
            }
        val cmakeOverrides =
            cmakeEnvironment
                .getOrElse(emptyMap())
                .toSortedMap()
                .map { (name, value) -> "$name=$value" }
        (probes + overrides + cmakeOverrides).joinToString("\n")
    }

// The signature is handed to the script as well as declared in the key, so there is exactly one
// definition of "the toolchain" and the CMake tree can be discarded whenever it stops matching. An
// argument provider is what allows both: its @Input is part of the key, and its value reaches the
// process. asArguments() is evaluated at execution time, so the version probes do not run on every
// configuration of this build.
abstract class NativeToolchainArgumentProvider : CommandLineArgumentProvider {
    @get:Input
    abstract val toolchain: Property<String>

    override fun asArguments(): Iterable<String> = listOf(toolchain.get())
}

val nativeAbiLayoutReport =
    layout.buildDirectory.file("reports/native-abi-layout/verification.txt")

val nativeAbiLayoutTest =
    tasks.register<Exec>("nativeAbiLayoutTest") {
        group = "verification"
        description = "Verifies deterministic native-only ABI layout generation."
        inputs
            .files(
                fileTree("src/main/cpp/cmake"),
                file("src/main/cpp/foundry_java_abi_layout.h.in"),
                rootProject.file("api/current/extension_api.json"),
                rootProject.file("api/current/provenance.json"),
            ).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(nativeAbiLayoutScript).withPathSensitivity(PathSensitivity.NONE)
        inputs.property("hostPlatform", hostPlatform)
        // Script mode only: no compiler participates, so cmake is the whole toolchain here.
        inputs.property("cmakeVersion", cmakeVersion)
        outputs.file(nativeAbiLayoutReport).withPropertyName("verificationReport")
        outputs.cacheIf("ABI layout generation and its rejections are deterministic") { true }
        workingDir = rootDir
        executable = "bash"
        args(
            rootRelative(nativeAbiLayoutScript.asFile),
            rootRelative(projectDir),
            rootRelative(nativeAbiLayoutReport.get().asFile),
        )
    }

// The CMake build tree stays undeclared, one level under the declared output, for two reasons: it
// holds absolute source and build paths in CMakeCache.txt that no other checkout could reuse, and
// leaving it out of the outputs is what lets a developer's C++ edit rebuild incrementally instead of
// from scratch. The report directory beside it is the small, relocatable evidence the CI job uploads
// and the build cache stores, so a hit is indistinguishable from an execution.
val nativeHostTest =
    tasks.register<Exec>("nativeHostTest") {
        group = "verification"
        description = "Builds and runs the JNI-free native lifecycle tests."
        inputs
            .files(
                fileTree("src/main/cpp"),
                fileTree("src/test/cpp"),
                fileTree("src/androidTest/cpp"),
                rootProject.fileTree("api/current"),
            ).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(runtimeContractSource).withPathSensitivity(PathSensitivity.NONE)
        inputs.file(nativeTestScript).withPathSensitivity(PathSensitivity.NONE)
        inputs.property("hostPlatform", hostPlatform)
        argumentProviders.add(
            objects.newInstance<NativeToolchainArgumentProvider>().apply {
                toolchain.set(nativeToolchain)
            },
        )
        outputs
            .dir(layout.buildDirectory.dir("native-host/report"))
            .withPropertyName("verificationReport")
        outputs.cacheIf("the host lifecycle tests are deterministic for a given platform") { true }
        workingDir = rootDir
        executable = "bash"
        args(rootRelative(nativeTestScript.asFile), "host", rootRelative(projectDir))
    }

val nativeSanitizerTest =
    tasks.register<Exec>("nativeSanitizerTest") {
        group = "verification"
        description = "Runs the native lifecycle tests under ASan and UBSan."
        inputs
            .files(
                fileTree("src/main/cpp"),
                fileTree("src/test/cpp"),
                fileTree("src/androidTest/cpp"),
                rootProject.fileTree("api/current"),
            ).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(runtimeContractSource).withPathSensitivity(PathSensitivity.NONE)
        inputs.file(nativeTestScript).withPathSensitivity(PathSensitivity.NONE)
        inputs.property("hostPlatform", hostPlatform)
        argumentProviders.add(
            objects.newInstance<NativeToolchainArgumentProvider>().apply {
                toolchain.set(nativeToolchain)
            },
        )
        outputs
            .dir(layout.buildDirectory.dir("native-host-sanitized/report"))
            .withPropertyName("verificationReport")
        outputs.cacheIf("the sanitized lifecycle tests are deterministic for a given platform") {
            true
        }
        workingDir = rootDir
        executable = "bash"
        args(rootRelative(nativeTestScript.asFile), "sanitizer", rootRelative(projectDir))
    }

tasks.named("check") {
    dependsOn(nativeAbiLayoutTest, nativeHostTest, nativeSanitizerTest)
}
