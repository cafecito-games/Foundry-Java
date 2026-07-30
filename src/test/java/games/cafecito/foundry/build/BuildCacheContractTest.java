package games.cafecito.foundry.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the build cache properties of the hand-written verifier tasks and of the plugin TestKit
 * suite.
 *
 * <p>These properties fail silently. A task that declares no outputs, or whose cache key embeds the
 * path of the checkout that produced it, leaves a correct and green build behind while never
 * reusing anything. {@code gradle/verify-build-cache-portability.sh} asserts the runtime behaviour
 * on the four tasks it is cheap to re-execute; what is asserted here is the declaration itself, for
 * the three tasks that gate is deliberately too expensive to cover.
 */
class BuildCacheContractTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Map<String, List<String>> CACHEABLE_VERIFIER_TASKS =
            Map.of(
                    "foundry-java-android/build.gradle.kts",
                    List.of("nativeAbiLayoutTest", "nativeHostTest", "nativeSanitizerTest"),
                    "foundry-java-runtime/build.gradle.kts",
                    List.of("generateFoundryApi", "verifyRuntimeApi", "verifyGeneratedRealization"),
                    "foundry-java-kotlin/build.gradle.kts",
                    List.of("verifyKotlinApi"));

    @Test
    void everyHandWrittenVerifierTaskDeclaresOutputsAndOptsIntoTheBuildCache() throws IOException {
        for (Map.Entry<String, List<String>> module : CACHEABLE_VERIFIER_TASKS.entrySet()) {
            String build = read(module.getKey());
            for (String task : module.getValue()) {
                String registration = registrationOf(build, task, module.getKey());
                // Declared outputs are what make an entry storable at all; cacheIf is what
                // makes a task type Gradle does not annotate as cacheable eligible to store
                // one. Either alone leaves the task re-executing with nothing reporting it.
                assertTrue(
                        registration.contains("outputs."),
                        task + " must declare outputs to be cacheable");
                assertTrue(
                        registration.contains("outputs.cacheIf("),
                        task + " must opt into the build cache");
            }
        }
    }

    @Test
    void noCacheableVerifierTaskNamesAPathThatOnlyItsOwnCheckoutCanResolve() throws IOException {
        // Exec and JavaExec put `args` and `executable` into the build cache key but not
        // `workingDir`, so relative arguments plus a repository-root working directory are what
        // make a stored entry readable by another checkout. The one absolute path still allowed
        // in each module is the Java toolchain location, which travels in the environment
        // because Exec treats that as @Internal.
        assertEquals(0, occurrences(read("foundry-java-android/build.gradle.kts"), "absolutePath"));
        assertEquals(1, occurrences(read("foundry-java-runtime/build.gradle.kts"), "absolutePath"));
        assertEquals(1, occurrences(read("foundry-java-kotlin/build.gradle.kts"), "absolutePath"));

        for (String module : List.of("foundry-java-android", "foundry-java-runtime")) {
            String build = read(module + "/build.gradle.kts");
            assertTrue(
                    build.contains(
                            "fun rootRelative(path: File): String ="
                                    + " path.relativeTo(rootDir).invariantSeparatorsPath"),
                    module
                            + " must name its cached task arguments relative to the repository root");
            assertTrue(build.contains("workingDir = rootDir"), module);
        }
        assertTrue(read("foundry-java-kotlin/build.gradle.kts").contains("workingDir = rootDir"));

        // The toolchain decides what these two inventories look like and neither script used
        // to name one, so the declared inputs did not determine the result and a cache hit was
        // not equivalent to an execution.
        for (String module : List.of("foundry-java-runtime", "foundry-java-kotlin")) {
            assertTrue(
                    read(module + "/build.gradle.kts").contains("\"FOUNDRY_JDK_BIN\""),
                    module + " must run the configured toolchain rather than PATH");
            assertTrue(
                    read(module + "/build.gradle.kts").contains("\"javaRuntimeVersion\""),
                    module + " must name the toolchain it ran in its cache key");
        }
        assertTrue(read("gradle/verify-runtime-api.sh").contains("${FOUNDRY_JDK_BIN:+"));
        assertTrue(
                read("foundry-java-kotlin/verify-kotlin-api.sh").contains("${FOUNDRY_JDK_BIN:+"));

        // The native tasks shell out to cmake, ctest and a host C++ compiler, none of which Gradle
        // can see. Without the toolchain in the key an entry produced under one compiler would
        // suppress the verification under another and the check would go green having run nothing,
        // so the host platform on its own is not enough.
        String androidBuild = read("foundry-java-android/build.gradle.kts");
        assertEquals(
                3, occurrences(androidBuild, "inputs.property(\"hostPlatform\", hostPlatform)"));
        assertEquals(2, occurrences(androidBuild, "toolchain.set(nativeToolchain)"));
        assertTrue(androidBuild.contains(": CommandLineArgumentProvider {"));
        assertTrue(androidBuild.contains("inputs.property(\"cmakeVersion\", cmakeVersion)"));

        // CMakeLists.txt reads RUNTIME_CONTRACT_VERSION out of this file and configures it into a
        // generated header, so it decides what the native tests compile even though it sits outside
        // every source tree they declare. It has to be an input of the tasks and a configure
        // dependency of CMake: the first stops a stale entry being replayed, the second stops a
        // kept
        // build tree from compiling the previous contract version.
        assertEquals(2, occurrences(androidBuild, "inputs.file(runtimeContractSource)"));
        assertTrue(
                androidBuild.contains(
                        "src/main/java/games/cafecito/foundry/runtime/FoundryRuntime.java"));
        String cmakeLists = read("foundry-java-android/src/main/cpp/CMakeLists.txt");
        int configureDepends = cmakeLists.indexOf("PROPERTY CMAKE_CONFIGURE_DEPENDS");
        assertTrue(configureDepends > 0);
        assertTrue(
                cmakeLists
                        .substring(configureDepends, cmakeLists.indexOf(")", configureDepends))
                        .contains("${FOUNDRY_JAVA_RUNTIME_SOURCE}"));
        // providers.exec is what keeps the version probes compatible with
        // --configuration-cache-problems=fail.
        assertTrue(androidBuild.contains(".exec {"));
        // Everything CMake reads from the environment is captured by prefix, not by name. A list of
        // names is a list that falls behind, and each omission is a silent false green.
        assertTrue(androidBuild.contains("environmentVariablesPrefixedBy(\"CMAKE_\")"));
        for (String variable : List.of("CC", "CXX", "CFLAGS", "CXXFLAGS", "LDFLAGS")) {
            assertTrue(
                    androidBuild.contains('"' + variable + '"'),
                    variable + " steers the compiler CMake selects and must be in the key");
        }
    }

    @Test
    void aCacheHitLeavesTheEvidenceTheCheckJobUploads() throws IOException {
        String androidBuild = read("foundry-java-android/build.gradle.kts");
        String workflow = read(".github/workflows/ci.yml");
        String nativeTests = read("gradle/run-native-tests.sh");

        // The CMake build tree records the absolute paths it was configured with, so it is not
        // declared and not cached; the relocatable report beside it is. Both sit under the
        // directory the check job uploads, so the evidence survives a hit.
        assertTrue(androidBuild.contains("layout.buildDirectory.dir(\"native-host/report\")"));
        assertTrue(
                androidBuild.contains(
                        "layout.buildDirectory.dir(\"native-host-sanitized/report\")"));
        assertTrue(nativeTests.contains("build_directory=\"$output_root/cmake\""));
        assertTrue(nativeTests.contains("report_directory=\"$output_root/report\""));

        // CMake reads CC, CXX and the flag variables only when a tree is first configured, so a
        // reused tree would test the previous toolchain's binaries and let Gradle store that result
        // under the new toolchain's key. The tree is discarded exactly when the signature changes,
        // which is what keeps the tree and the key describing the same thing.
        assertTrue(
                nativeTests.contains(
                        "toolchain_stamp=\"$build_directory/foundry-java-toolchain.stamp\""));
        assertTrue(nativeTests.contains("rm -rf \"$build_directory\""));
        assertTrue(
                nativeTests.contains("printf '%s' \"$toolchain_signature\" >\"$toolchain_stamp\""));
        assertFalse(
                androidBuild.contains("outputs.dir(layout.buildDirectory.dir(\"native-host\"))"));
        assertTrue(workflow.contains("foundry-java-android/build/native-host/**"));
        assertTrue(workflow.contains("foundry-java-android/build/native-host-sanitized/**"));
        assertTrue(workflow.contains("foundry-java-runtime/build/reports/foundry-realization/**"));
        assertTrue(workflow.contains("**/build/reports/**"));

        // The two tasks that had no outputs at all now publish the inventory they computed,
        // which is both the cacheable artifact and what a developer reads when a baseline
        // disagrees.
        assertTrue(read("gradle/verify-runtime-api.sh").contains("cp \"$actual\" \"$report\""));
        assertTrue(
                read("foundry-java-kotlin/verify-kotlin-api.sh")
                        .contains("cp \"$actual\" \"$report\""));
        assertTrue(read("gradle/verify-native-abi-layout.sh").contains("} >\"$report\""));
    }

    @Test
    void thePluginSuiteKeepsTheHeavyArtifactInputsOnTheOneTaskThatReadsThem() throws IOException {
        String build = read("foundry-java-gradle-plugin/build.gradle.kts");
        String suite =
                read(
                        "foundry-java-gradle-plugin/src/test/java/games/cafecito/foundry/gradle/"
                                + "FoundryJavaPluginTest.java");

        // The release AAR carries four ABIs of compiled C++. While the whole suite declared it, any
        // change under src/main/cpp invalidated a multi-minute TestKit suite that is otherwise
        // testing plugin wiring against synthetic fixtures.
        assertEquals(1, occurrences(suite, "@Tag(\"actualArtifacts\")"));
        assertEquals(1, occurrences(build, "val actualArtifactTag = \"actualArtifacts\""));
        assertTrue(build.contains("excludeTags(actualArtifactTag)"));
        assertTrue(build.contains("includeTags(actualArtifactTag)"));
        assertTrue(build.contains("tasks.register<Test>(\"actualArtifactTest\")"));
        assertTrue(build.contains("dependsOn(actualArtifactTest)"));

        // Only the task that reads them may depend on them, or narrowing the inputs buys nothing.
        assertEquals(
                1,
                occurrences(
                        build,
                        "dependsOn(\":foundry-java-android:bundleReleaseAar\","
                                + " \":foundry-java-runtime:jar\")"));
        assertTrue(build.contains("androidAar.set(actualAndroidAar)"));
        assertTrue(build.contains("runtimeJar.set(actualRuntimeJar)"));
    }

    @Test
    void thePluginSuiteCacheKeyCarriesNoCheckoutPath() throws IOException {
        String build = read("foundry-java-gradle-plugin/build.gradle.kts");

        // Test.systemProperties is an @Input, so a system property holding a checkout path made
        // every stored entry private to the machine that produced it. An argument provider
        // supplies the same flags at execution time while declaring only normalized inputs.
        assertFalse(build.contains("systemProperty("));
        assertEquals(3, occurrences(build, "jvmArgumentProviders.add("));
        assertEquals(3, occurrences(build, ": CommandLineArgumentProvider {"));
        assertEquals(3, occurrences(build, "PathSensitivity.NONE"));
        assertTrue(build.contains("@get:Classpath"));

        // The suite is still serial. Neither lever #63 proposed works here: maxParallelForks
        // distributes test classes and this module has two, one holding all but a handful of the
        // tests, and JUnit Platform per-method parallelism produced no wall-clock gain because each
        // test slows in proportion to the concurrency. See the follow-up issue linked from #63.
        assertFalse(build.contains("maxParallelForks"));
        assertFalse(
                Files.exists(
                        ROOT.resolve(
                                "foundry-java-gradle-plugin/src/test/resources/"
                                        + "junit-platform.properties")));
    }

    @Test
    void ciProvesVerifierCacheEntriesAreReplayableAtAnotherCheckoutPath() throws IOException {
        String workflow = read(".github/workflows/ci.yml");
        String gate = read("gradle/verify-build-cache-portability.sh");

        assertTrue(workflow.contains("bash gradle/verify-build-cache-portability.sh"));
        // The configuration cache proof still has to run: the two gates catch different
        // failures and neither subsumes the other.
        assertTrue(workflow.contains("bash gradle/verify-configuration-cache-reuse.sh"));
        assertTrue(workflow.contains("bash gradle/verify-configuration-cache-reuse-selftest.sh"));
        assertTrue(gate.contains("set -euo pipefail"));

        // Two copies at deliberately different path lengths, both stripped of output
        // directories: a task whose outputs are already present reports UP-TO-DATE and stores
        // nothing, so asserting against the checkout under test would let an empty cache pass.
        assertTrue(gate.contains("store_directory=\"$workspace/a\""));
        assertTrue(
                gate.contains(
                        "replay_directory=\"$workspace/"
                                + "replayed-from-a-much-longer-checkout-path\""));
        assertTrue(gate.contains("--exclude 'build/'"));
        assertTrue(gate.contains("--exclude '.gradle/'"));
        assertTrue(gate.contains("run_tasks \"$store_directory\" \"$store_log\""));
        assertTrue(gate.contains("run_tasks \"$replay_directory\" \"$replay_log\""));
        assertTrue(gate.contains("grep -Fq \"> Task $task FROM-CACHE\" \"$replay_log\""));
        assertTrue(gate.contains("--no-daemon"));
        assertTrue(gate.contains("--build-cache"));

        // The default set is partial on purpose, and the reason is recorded where it is decided
        // rather than only in the pull request that narrowed it.
        assertTrue(gate.contains(":foundry-java-runtime:verifyRuntimeApi"));
        assertTrue(gate.contains(":foundry-java-runtime:verifyGeneratedRealization"));
        assertTrue(gate.contains(":foundry-java-kotlin:verifyKotlinApi"));
        assertTrue(gate.contains(":foundry-java-android:nativeAbiLayoutTest"));
        assertTrue(gate.contains("The default task set is deliberately partial."));
    }

    @Test
    void thePortabilityGateIsProvenToStillFailBothDefectsItCatches() throws IOException {
        String workflow = read(".github/workflows/ci.yml");
        String selfTest = read("gradle/verify-build-cache-portability-selftest.sh");
        String absoluteFixture =
                read(
                        "gradle/testFixtures/build-cache-portability/absolute-path-argument"
                                + "/build.gradle.kts");
        String undeclaredFixture =
                read(
                        "gradle/testFixtures/build-cache-portability/undeclared-output"
                                + "/build.gradle.kts");

        assertTrue(workflow.contains("bash gradle/verify-build-cache-portability-selftest.sh"));
        assertTrue(selfTest.contains("set -euo pipefail"));

        // The self-test must drive the real gate, not a paraphrase of it, or it proves nothing
        // about what CI runs.
        assertTrue(
                selfTest.contains(
                        "gate=\"$repository_root/gradle/verify-build-cache-portability.sh\""));
        assertTrue(selfTest.contains("bash \"$gate\" \"$workspace/$fixture\""));

        // Each fixture must be rejected, and rejected by the gate's own diagnostic: a fixture whose
        // build simply broke would exit non-zero without producing that line, and that has to read
        // as a failure rather than as proof.
        assertTrue(
                selfTest.contains(
                        "expect_rejection absolute-path-argument \\\n"
                                + "  'puts its own absolute output path into a cached task"
                                + " argument' \\\n"
                                + "  'Task :probe was not replayed from the build cache by a"
                                + " differently-pathed checkout'"));
        assertTrue(
                selfTest.contains(
                        "expect_rejection undeclared-output \\\n"
                                + "  'declares no outputs, so Gradle stores nothing for it' \\\n"
                                + "  'Task :probe was not replayed from the build cache by a"
                                + " differently-pathed checkout'"));

        // One fixture stores an entry no other path can read; the other stores nothing at all. Both
        // shapes were present in this repository's verifier tasks.
        assertTrue(absoluteFixture.contains("outputs.file(report).withPropertyName(\"report\")"));
        assertTrue(absoluteFixture.contains("$reportPath"));
        assertFalse(undeclaredFixture.contains("outputs.file"));
        assertFalse(undeclaredFixture.contains("outputs.dir"));
        assertTrue(undeclaredFixture.contains("outputs.cacheIf("));
    }

    @Test
    void theseContractSuitesAreThemselvesExcludedFromReplay() throws IOException {
        String rootBuild = read("build.gradle.kts");

        // These suites read build scripts, workflows, gate scripts and whole module source trees
        // directly rather than receiving them as task inputs, so their true input is the
        // repository.
        // Enumerating that here would be a list that silently falls behind the assertions, and an
        // up-to-date check or a cache hit would then replay a pass across a change to the very file
        // being guarded — the exact failure the rest of this class exists to prevent.
        assertTrue(rootBuild.contains("tasks.named<Test>(\"test\") {"));
        assertTrue(rootBuild.contains("outputs.upToDateWhen { false }"));
        assertTrue(
                rootBuild.contains(
                        "outputs.cacheIf("
                                + "\"the repository contract suites read files no input set fully"
                                + " describes\") { false }"));
    }

    private static String registrationOf(String build, String task, String module) {
        // Registered either as tasks.register<T>("name") or as val name by tasks.registering(T).
        int start = build.indexOf('"' + task + '"');
        if (start < 0) {
            start = build.indexOf("val " + task + " by tasks.registering");
        }
        assertTrue(start >= 0, task + " must be registered in " + module);
        // A registration ends where the next top-level declaration begins, which is the next line
        // starting in column zero. Stopping at the next tasks.register would instead stop at the
        // dependsOn(tasks.named(...)) inside the block being read.
        java.util.regex.Matcher end =
                java.util.regex.Pattern.compile("\\n\\S")
                        .matcher(build)
                        .region(start, build.length());
        return build.substring(start, end.find() ? end.start() : build.length());
    }

    private static int occurrences(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static String read(String path) throws IOException {
        return Files.readString(ROOT.resolve(path));
    }
}
