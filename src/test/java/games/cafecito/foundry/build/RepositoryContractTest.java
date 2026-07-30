package games.cafecito.foundry.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RepositoryContractTest {
    private static final String LOCK_COMMAND = "./gradlew --write-locks resolveAndLockAll";
    private static final String UPLOAD_ARTIFACT_COMMIT =
            "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02";
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Set<String> DEBUG_FIXTURE_CLASSES =
            Set.of(
                    "FoundryJavaStartupEvidence",
                    "FoundryJavaTestActivity",
                    "FoundryJavaTestApplication",
                    "FoundryJavaTestHost",
                    "FoundryJavaTestRegistry",
                    "FoundryJavaTestStartupProvider");
    private static final List<String> MODULES =
            List.of(
                    "foundry-java-api-model",
                    "foundry-java-generator",
                    "foundry-java-annotations",
                    "foundry-java-processor",
                    "foundry-java-runtime",
                    "foundry-java-android",
                    "foundry-java-gradle-plugin",
                    "foundry-java-kotlin",
                    "foundry-java-test");
    private static final List<String> HOST_NEUTRAL_MODULES =
            List.of(
                    "foundry-java-annotations",
                    "foundry-java-api-model",
                    "foundry-java-generator",
                    "foundry-java-gradle-plugin",
                    "foundry-java-kotlin",
                    "foundry-java-processor",
                    "foundry-java-runtime",
                    "foundry-java-test");
    private static final List<String> LOCK_FILES =
            List.of(
                    "gradle.lockfile",
                    "settings-gradle.lockfile",
                    "foundry-java-android/gradle.lockfile",
                    "foundry-java-annotations/gradle.lockfile",
                    "foundry-java-api-model/gradle.lockfile",
                    "foundry-java-generator/gradle.lockfile",
                    "foundry-java-gradle-plugin/gradle.lockfile",
                    "foundry-java-kotlin/gradle.lockfile",
                    "foundry-java-processor/gradle.lockfile",
                    "foundry-java-runtime/gradle.lockfile",
                    "foundry-java-test/gradle.lockfile");

    @Test
    void repositoryDeclaresTheCompleteJavaFirstAndroidOnlyContract() throws IOException {
        String settings = read("settings.gradle.kts");
        String rootBuild = read("build.gradle.kts");
        String properties = read("gradle.properties");

        assertEquals(9, MODULES.size());
        for (String module : MODULES) {
            assertTrue(
                    settings.contains("\":%s\"".formatted(module)), module + " must be included");
            assertTrue(Files.isDirectory(ROOT.resolve(module)), module + " must exist");
        }
        assertTrue(rootBuild.contains("JavaLanguageVersion.of(17)"));
        assertTrue(rootBuild.contains("games.cafecito.foundry"));
        assertTrue(rootBuild.contains("lockAllConfigurations"));
        assertTrue(rootBuild.contains("isPreserveFileTimestamps = false"));
        assertTrue(rootBuild.contains("isReproducibleFileOrder = true"));
        assertTrue(properties.contains("org.gradle.caching=true"));
        assertTrue(
                properties.contains(
                        "org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g"
                                + " -Dfile.encoding=UTF-8"));
        assertTrue(
                read("foundry-java-gradle-plugin/build.gradle.kts")
                        .contains("games.cafecito.foundry.java"));
        assertTrue(read("gradle/libs.versions.toml").contains("com.android.library"));
    }

    @Test
    void platformBoundariesProtectThePublicJavaAbi() throws IOException {
        String rootBuild = read("build.gradle.kts");

        assertEquals(MODULES.size() - 1, HOST_NEUTRAL_MODULES.size());
        assertTrue(rootBuild.contains("val requiredHostNeutralProjects ="));
        for (String module : HOST_NEUTRAL_MODULES) {
            assertFalse(containsAndroidSourceDeclaration(module), module);
            assertTrue(rootBuild.contains("\":%s\" to".formatted(module)), module);
        }
        assertTrue(
                rootBuild.contains(
                        "expectedBoundaryDependencies.get().keys"
                                + " == requiredHostNeutralProjectPaths.get()"));
        assertTrue(read("foundry-java-kotlin/build.gradle.kts").contains("foundry-java-runtime"));
        assertTrue(read("foundry-java-android/build.gradle.kts").contains("foundry-java-runtime"));
        assertFalse(readTree("foundry-java-android").contains("libfoundry_android.so"));
    }

    @Test
    void generatorPublishesApiModelAndARealGeneratorOnlyConsumerCompiles() throws IOException {
        String generatorBuild = read("foundry-java-generator/build.gradle.kts");
        String rootBuild = read("build.gradle.kts");

        assertTrue(generatorBuild.contains("api(project(\":foundry-java-api-model\"))"));
        assertFalse(
                generatorBuild.contains("implementation(project(\":foundry-java-api-model\"))"));
        assertTrue(rootBuild.contains("generatorOnlyConsumer"));
        assertTrue(rootBuild.contains("compileGeneratorOnlyConsumer"));
        assertTrue(
                Files.isRegularFile(
                        ROOT.resolve(
                                "src/generatorOnlyConsumer/java/"
                                        + "games/cafecito/foundry/build/GeneratorOnlyConsumer.java")));
    }

    @Test
    void androidSourceScanOnlyMatchesPackageAndImportDeclarations() {
        assertFalse(
                containsAndroidDeclaration(
                        """
                        // android.view.View is documentation, not a dependency.
                        final class Example {
                            String value = "import android.view.View;";
                        }
                        """));
        assertFalse(
                containsAndroidDeclaration(
                        """
                        /*
                         * import android.view.View;
                         */
                        final class Example {}
                        """));
        assertFalse(
                containsAndroidDeclaration(
                        "val documentation = \"\"\"\nimport android.view.View;\n\"\"\""));
        assertTrue(containsAndroidDeclaration("import android.view.View;"));
        assertTrue(containsAndroidDeclaration("import static android.os.Build.VERSION;"));
        assertTrue(containsAndroidDeclaration("package android.example;"));
    }

    @Test
    void boundaryDependencyNormalizationPreservesFileIdentityAndMultiplicity() throws IOException {
        String rootBuild = read("build.gradle.kts");

        assertTrue(rootBuild.contains("FileCollectionDependency"));
        assertTrue(rootBuild.contains("stableBoundaryFileSignature"));
        assertTrue(rootBuild.contains("gradle-api-files"));
        assertTrue(rootBuild.contains("gradle-test-kit-files"));
        assertTrue(rootBuild.contains("project-files"));
        assertTrue(rootBuild.contains("declaredFiles.buildDependencies"));
        assertTrue(rootBuild.contains("Unsupported file collection dependency"));
        assertFalse(rootBuild.contains("dependency.files.files"));
        assertFalse(rootBuild.contains("MessageDigest"));
        assertFalse(rootBuild.contains(".toSortedSet()"));
    }

    @Test
    void ciPinsTheAndroidPackagesRequiredByTheCompileSdk() throws IOException {
        String androidBuild = read("foundry-java-android/build.gradle.kts");
        String catalog = read("gradle/libs.versions.toml");
        String workflow = read(".github/workflows/ci.yml");
        var compileSdkMatcher = Pattern.compile("compileSdk\\s*=\\s*(\\d+)").matcher(androidBuild);
        var buildToolsMatcher =
                Pattern.compile("android-build-tools\\s*=\\s*\"([^\"]+)\"").matcher(catalog);

        assertTrue(compileSdkMatcher.find());
        assertTrue(buildToolsMatcher.find());
        assertEquals("36", compileSdkMatcher.group(1));
        assertEquals("35.0.0", buildToolsMatcher.group(1));
        assertTrue(catalog.contains("android-gradle-plugin = \"8.10.0\""));
        assertTrue(catalog.contains("desugar-jdk-libs = \"2.1.5\""));
        assertTrue(
                catalog.contains(
                        "desugar-jdk-libs = { module = \"com.android.tools:desugar_jdk_libs\""));
        assertTrue(androidBuild.contains("isCoreLibraryDesugaringEnabled = true"));
        assertTrue(androidBuild.contains("coreLibraryDesugaring(libs.desugar.jdk.libs)"));
        String androidLock = read("foundry-java-android/gradle.lockfile");
        assertTrue(
                androidLock.contains(
                        "com.android.tools:desugar_jdk_libs:2.1.5=coreLibraryDesugaring"));
        assertFalse(
                Pattern.compile("empty=.*\\bcoreLibraryDesugaring\\b").matcher(androidLock).find());
        assertTrue(
                Pattern.compile(
                                "buildToolsVersion\\s*=\\s*"
                                        + "libs\\.versions\\.android\\.build\\.tools\\s*"
                                        + "\\.get\\(\\)",
                                Pattern.DOTALL)
                        .matcher(androidBuild)
                        .find());
        assertTrue(
                Pattern.compile(
                                "packages:\\s*>-\\s*"
                                        + "tools platform-tools platforms;android-"
                                        + compileSdkMatcher.group(1)
                                        + " build-tools;"
                                        + Pattern.quote(buildToolsMatcher.group(1))
                                        + "\\s+ndk;29\\.0\\.14206865 cmake;3\\.22\\.1")
                        .matcher(workflow)
                        .find());
    }

    @Test
    void buildToolingIsPinnedFormattedAndConfigurationCacheSafe() throws IOException {
        String rootBuild = read("build.gradle.kts");
        String wrapper = read("gradle/wrapper/gradle-wrapper.properties");
        String workflow = read(".github/workflows/ci.yml");
        Pattern immutableAction = Pattern.compile("^[0-9a-f]{40}(?:\\s+#.*)?$");

        assertTrue(
                wrapper.contains(
                        "distributionSha256Sum="
                                + "f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6"));
        workflow.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("- uses: "))
                .forEach(
                        line ->
                                assertTrue(
                                        immutableAction
                                                .matcher(line.substring(line.indexOf('@') + 1))
                                                .matches(),
                                        line + " must use an immutable commit SHA"));
        assertTrue(rootBuild.contains("target(\"*/src/**/*.kt\")"));
        assertTrue(rootBuild.contains("ktlint(\"1.3.1\")"));
        assertTrue(rootBuild.contains("lineEndings = LineEnding.UNIX"));
        assertTrue(rootBuild.contains("https://github.com/diffplug/spotless/issues/2431"));
        assertTrue(rootBuild.contains("tasks.named(\"check\") {"));
        assertTrue(rootBuild.contains("\"spotlessCheck\", \"verifyRepositoryContract\""));
        assertFalse(rootBuild.contains("notCompatibleWithConfigurationCache"));
    }

    @Test
    void repositoryUsesCanonicalLockAndBuildLocalPublicationWorkflows() throws IOException {
        for (String documentation :
                List.of("AGENTS.md", "README.md", "CONTRIBUTING.md", "docs/releasing.md")) {
            assertTrue(read(documentation).contains(LOCK_COMMAND), documentation);
        }
        String workflow = read(".github/workflows/ci.yml");
        assertTrue(workflow.contains("- run: " + LOCK_COMMAND));

        String rootBuild = read("build.gradle.kts");
        assertTrue(rootBuild.contains("layout.buildDirectory.dir(\"repository\")"));
        assertTrue(rootBuild.contains("VerifyPublications"));
        assertFalse(rootBuild.contains("publishReleasePublicationToMavenLocal"));
    }

    @Test
    void repositoryPinsTheExactLockInventoryAndCiRejectsAllLockDrift() throws IOException {
        String rootBuild = read("build.gradle.kts");
        String workflow = read(".github/workflows/ci.yml");

        assertEquals(11, LOCK_FILES.size());
        assertTrue(rootBuild.contains("val requiredLockFilePaths ="));
        for (String lockFile : LOCK_FILES) {
            assertTrue(Files.isRegularFile(ROOT.resolve(lockFile)), lockFile + " must exist");
            assertTrue(
                    rootBuild.contains("\"%s\"".formatted(lockFile)),
                    lockFile + " must be part of the typed verifier contract");
        }
        assertTrue(workflow.contains("git status --porcelain --untracked-files=all --"));
        assertTrue(workflow.contains("':(glob)**/gradle.lockfile'"));
        assertFalse(workflow.contains("git diff --exit-code -- gradle.lockfile"));
    }

    @Test
    void publicationVerificationUsesAnIndependentExactTopology() throws IOException {
        String rootBuild = read("build.gradle.kts");

        assertTrue(rootBuild.contains("val requiredPublicationCoordinates ="));
        assertTrue(rootBuild.contains("val requiredPomDependencies ="));
        assertTrue(rootBuild.contains("val requiredModuleDependencies ="));
        assertTrue(rootBuild.contains("val requiredModuleArtifactNames ="));
        assertTrue(rootBuild.contains("games.cafecito.foundry.java.gradle.plugin"));
        assertTrue(rootBuild.contains("foundry-java-gradle-plugin"));
        assertTrue(rootBuild.contains("foundry-java-runtime"));
        assertTrue(rootBuild.contains("org.jetbrains.kotlin|kotlin-stdlib|2.0.21"));
        assertTrue(
                rootBuild.contains(
                        "runtime|org.jetbrains.kotlinx|kotlinx-coroutines-core-jvm|1.9.0"));
        assertTrue(rootBuild.contains("org.jetbrains.kotlinx|kotlinx-coroutines-core|1.9.0"));
        assertFalse(
                rootBuild.contains(
                        "compile|org.jetbrains.kotlinx|kotlinx-coroutines-core-jvm|1.9.0"));
        assertTrue(rootBuild.contains("check(poms.size == 10)"));
        assertTrue(rootBuild.contains("check(modules.size == 9)"));
        assertTrue(rootBuild.contains("check(jarCount == 8 && aarCount == 1)"));
        assertFalse(rootBuild.contains("val expectedPoms = mutableMapOf"));
        assertFalse(rootBuild.contains("publication.artifacts.forEach"));
    }

    @Test
    void ciProvesConfigurationCacheReuseWithoutMaskingGradleFailures() throws IOException {
        String workflow = read(".github/workflows/ci.yml");
        String cacheVerification = read("gradle/verify-configuration-cache-reuse.sh");

        assertTrue(workflow.contains("bash gradle/verify-configuration-cache-reuse.sh"));
        assertTrue(cacheVerification.contains("set -euo pipefail"));
        assertTrue(cacheVerification.contains("rm -rf \"$repo_root/build\""));
        assertTrue(
                cacheVerification.contains(
                        "find \"$repo_root\" -mindepth 2 -maxdepth 2"
                                + " -type d -name build -exec rm -rf {} +"));
        assertEquals(2, cacheVerification.split("\"\\$\\{gradle_command\\[@]}\"", -1).length - 1);
        assertTrue(cacheVerification.contains("tee \"$second_log\""));
        assertTrue(
                cacheVerification.contains(
                        "Configuration cache entry reused|Reusing configuration cache"));
        assertTrue(cacheVerification.contains("configuration cache cannot be reused"));
        assertTrue(cacheVerification.contains("--configuration-cache-problems=fail"));
    }

    @Test
    void androidArtifactPolicyUsesAnExactBootstrapClassAllowlist() throws IOException {
        String rootBuild = read("build.gradle.kts");

        assertTrue(
                rootBuild.contains(
                        "allowedBootstrapAndroidClasses =\n"
                                + "    setOf(\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryJavaInitializer\\$DiagnosticCallbacks.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryJavaInitializer\\$DiagnosticSink.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryJavaInitializer\\$NativeBootstrap.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryJavaInitializer\\$NativeLibrary.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryJavaInitializer\\$NativeLoader.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryJavaInitializer\\$PrimingState\\$Phase.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryJavaInitializer\\$PrimingState.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryJavaInitializer.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryJavaStartupProvider\\$Primer.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryJavaStartupProvider.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryNativeEngine\\$1.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryNativeEngine\\$2.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryNativeEngine\\$JniNativeGateway.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryNativeEngine\\$NativeDecodedObject.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryNativeEngine\\$NativeGateway.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryNativeEngine\\$NativeVariantSnapshot.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryNativeEngine\\$SignalBackend\\$ConnectedCallable.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryNativeEngine\\$SignalBackend.class\",\n"
                                + "        \"games/cafecito/foundry/java/"
                                + "FoundryNativeEngine.class\",\n"
                                + "    )"));
        int allowlistStart = rootBuild.indexOf("val allowedBootstrapAndroidClasses =");
        int allowlistEnd =
                rootBuild.indexOf("val requiredAndroidNativeLibraries =", allowlistStart);
        String allowlist = rootBuild.substring(allowlistStart, allowlistEnd);
        assertFalse(allowlist.contains("games/cafecito/foundry/generated/"));
        assertFalse(allowlist.contains("games/cafecito/foundry/runtime/"));
        assertFalse(allowlist.contains("FoundryGenerated"));
        assertFalse(allowlist.contains("*"));
        assertTrue(rootBuild.contains("dependsOn(\":foundry-java-android:bundleReleaseAar\")"));
        assertTrue(rootBuild.contains("check(actualClasses == allowedClasses.get())"));
        assertTrue(rootBuild.contains("expectedFixedConfiguration"));
        assertTrue(rootBuild.contains("expectedConsumerRules"));
        assertTrue(rootBuild.contains("Release AAR manifest must not declare an application"));
        assertTrue(rootBuild.contains("Release AAR manifest must not declare a provider"));
        assertFalse(rootBuild.contains("substringAfterLast('/').contains(\"Host\")"));
        assertTrue(rootBuild.contains("libfoundry_android.so"));
    }

    @Test
    void productionStartupAcceptanceScriptFreezesTwoFreshProcessRuns() throws IOException {
        String script = read("gradle/run-android-production-startup-acceptance.sh");

        assertTrue(script.startsWith("#!/usr/bin/env bash\nset -euo pipefail\n"));
        assertTrue(script.contains("${1:-emulator-5554}"));
        assertTrue(script.contains("/platform-tools/adb"));
        assertTrue(script.contains("ro.build.version.sdk"));
        assertTrue(script.contains("\"36\""));
        assertTrue(
                script.contains(
                        "./gradlew --no-daemon :foundry-java-android:assembleDebugAndroidTest"));
        assertTrue(script.contains("llvm-readelf"));
        assertTrue(script.contains("libfoundry_java_test_host.so"));
        assertTrue(script.contains("JNI_OnLoad"));
        assertTrue(script.contains("libfoundry_java.so"));
        assertEquals(1, occurrences(script, "build/outputs/apk/androidTest/debug/"));
        assertEquals(1, occurrences(script, "adb\" install"));
        assertTrue(script.contains("target_package=\"games.cafecito.foundry.android.test\""));
        assertTrue(
                script.contains(
                        "instrumentation_component=\"games.cafecito.foundry.android.test/"
                                + "games.cafecito.foundry.java.FoundryJavaInstrumentation\""));
        assertTrue(script.contains("force-stop"));
        assertTrue(script.contains("pidof"));
        assertTrue(script.contains("instrumentation_process=$!"));
        assertTrue(script.contains("wait \"$instrumentation_process\""));
        assertTrue(script.contains("-e foundry_run_index \"$run_index\""));
        assertTrue(
                script.contains(
                        "/data/user/0/games.cafecito.foundry.android.test/files/"
                                + "foundry-java-production-startup-evidence.json"));
        assertTrue(script.contains(".target_package == \"games.cafecito.foundry.android.test\""));
        assertTrue(
                script.contains(
                        ".authority == \"games.cafecito.foundry.android.test."
                                + "foundry-java-startup\""));
        assertTrue(
                script.contains("foundry-java-production-startup/run-${run_index}/evidence.json"));
        assertTrue(script.contains("instrumentation.txt"));
        assertTrue(script.contains("logcat.txt"));
        assertTrue(script.contains("emulator-diagnostics.txt"));
        assertTrue(script.contains("trap capture_diagnostics EXIT"));
        for (String field :
                List.of(
                        "schema_version",
                        "run_index",
                        "pid",
                        "pid_before_lifecycle",
                        "pid_after_lifecycle",
                        "target_package",
                        "authority",
                        "fresh_process",
                        "provider_before_application",
                        "provider_before_activity",
                        "context_count_during_priming",
                        "core_context_nonzero",
                        "provider_registration_count",
                        "application_on_create_count",
                        "activity_on_create_count",
                        "callback_dispatch_count",
                        "callback_result",
                        "callback_thread_attached",
                        "exception_contained",
                        "stale_instance_callback_rejected",
                        "invalidation_count",
                        "registration_order",
                        "teardown_order",
                        "events",
                        "result",
                        "failure")) {
            assertTrue(script.contains("." + field), field);
        }
        for (String event :
                List.of(
                        "provider_on_create",
                        "application_on_create",
                        "activity_on_create",
                        "foundry_extension_entry",
                        "core_initialize",
                        "scene_initialize",
                        "callback_dispatch",
                        "scene_deinitialize",
                        "core_deinitialize",
                        "context_invalidate")) {
            assertTrue(script.contains(event), event);
        }
        assertTrue(script.contains("summary.json"));
        assertTrue(script.contains("api_level: 36"));
        assertTrue(script.contains("serial: $serial"));
        assertTrue(script.contains("force_stop_observed: true"));
        assertTrue(script.contains("distinct_pids: true"));
        assertTrue(script.contains("jq"));
    }

    @Test
    void productionStartupAcceptanceRejectsForbiddenHostLibraryBeforeInstall() throws IOException {
        String script = read("gradle/run-android-production-startup-acceptance.sh");

        int archiveScan = script.indexOf("unzip -Z1 \"$test_apk\"");
        int install = script.indexOf("ANDROID_SERIAL=\"$serial\" \"$adb\" install");
        assertTrue(archiveScan >= 0, "missing instrumentation APK archive scan");
        assertTrue(script.contains("awk -F/"));
        assertTrue(script.contains("$1 == \"lib\" && NF == 3"));
        assertTrue(script.contains("$3 == \"libfoundry_android.so\""));
        assertTrue(script.contains("Instrumentation APK must not package libfoundry_android.so."));
        assertTrue(install > archiveScan, "forbidden host library scan must precede install");
    }

    @Test
    void productionStartupHostValidationMatchesDeviceEvidence() throws IOException {
        String script = read("gradle/run-android-production-startup-acceptance.sh");
        String normalized = script.replaceAll("\\s+", " ");

        assertTrue(script.contains(".events == $required_events"));
        assertTrue(script.contains(".registered_class_count_during_priming == 0"));
        assertTrue(script.contains(".descriptor_evaluation_count == 1"));
        assertTrue(script.contains(".callback_result_observed_in_java == 42"));
        assertTrue(script.contains(".exception_dispatch_count == 1"));
        assertTrue(script.contains(".exception_default_is_nil == true"));
        assertTrue(script.contains(".native_lifecycle as $lifecycle"));
        assertTrue(script.contains("$lifecycle.entry_accepted == true"));
        assertTrue(script.contains("$lifecycle.context_handle == 1"));
        assertTrue(
                normalized.contains(
                        "$lifecycle.initialize_attempts == [ \"CORE\", \"CORE\", \"SERVERS\","
                                + " \"SERVERS\", \"SCENE\", \"SCENE\" ]"));
        assertTrue(
                normalized.contains(
                        "$lifecycle.deinitialize_attempts == [ \"SCENE\", \"SCENE\", \"SERVERS\","
                                + " \"SERVERS\", \"CORE\", \"CORE\" ]"));
        assertTrue(
                normalized.contains(
                        "$lifecycle.registration_counts == { \"FoundryJavaTestCore\": 1,"
                                + " \"FoundryJavaTestScene\": 1 }"));
        assertTrue(
                normalized.contains(
                        "$lifecycle.unregistration_counts == { \"FoundryJavaTestCore\": 1,"
                                + " \"FoundryJavaTestScene\": 1 }"));
        assertTrue(script.contains("$lifecycle.live_instances_after_teardown == 0"));
        assertTrue(script.contains("$lifecycle.live_handles_after_teardown == 0"));
        assertTrue(script.contains("$lifecycle.entry_active_after_teardown == false"));
        assertTrue(script.contains("$lifecycle.events == $native_events"));
    }

    @Test
    void ciPublishesImmutableCheckAndProductionStartupEvidence() throws IOException {
        String workflow = read(".github/workflows/ci.yml");

        assertTrue(workflow.contains("bash gradle/run-android-production-startup-acceptance.sh"));
        assertFalse(workflow.contains(":foundry-java-android:connectedDebugAndroidTest"));
        assertEquals(2, occurrences(workflow, UPLOAD_ARTIFACT_COMMIT));
        assertTrue(workflow.contains("name: foundry-java-check-evidence"));
        assertTrue(workflow.contains("name: foundry-java-api36-production-startup-evidence"));
        assertEquals(2, occurrences(workflow, "if: always()"));
        int buildStepStart = workflow.indexOf("- name: Build, test, and inspect the native bridge");
        assertTrue(buildStepStart >= 0);
        int buildStepEnd = workflow.indexOf("\n      - ", buildStepStart + 1);
        assertTrue(buildStepEnd > buildStepStart);
        String buildStep = workflow.substring(buildStepStart, buildStepEnd);
        assertTrue(buildStep.contains("shell: bash"));
        assertTrue(buildStep.contains("run: |\n          set -euo pipefail"));
        assertTrue(
                buildStep.contains(
                        ":foundry-java-android:nativeSanitizerTest 2>&1 |\n"
                                + "            tee \"${RUNNER_TEMP}/foundry-java-check.log\""));
        assertTrue(
                buildStep.contains(
                        "foundry-java-android-release.aar 2>&1 |\n"
                                + "            tee \"${RUNNER_TEMP}/"
                                + "foundry-java-native-verifier.log\""));
        assertTrue(workflow.contains("${{ runner.temp }}/foundry-java-check.log"));
        assertTrue(workflow.contains("${{ runner.temp }}/foundry-java-native-verifier.log"));
        assertTrue(workflow.contains("foundry-java-android/build/native-host"));
        assertTrue(workflow.contains("foundry-java-android/build/native-host-sanitized"));
        assertTrue(workflow.contains("foundry-java-android/build/outputs/aar"));
        assertTrue(workflow.contains("merged_manifest"));
        assertTrue(workflow.contains("foundry-java-production-startup"));
        assertTrue(workflow.contains("foundry-java-emulator.log"));
        assertTrue(
                workflow.contains("foundry-java-android/build/outputs/apk/androidTest/debug/**"));
        assertTrue(
                workflow.contains(
                        "foundry-java-android/build/intermediates/merged_manifest/debug/**"));
        assertTrue(
                workflow.contains(
                        "foundry-java-android/build/intermediates/packaged_manifests/"
                                + "debugAndroidTest/**"));
        assertTrue(workflow.contains("foundry-java-android/build/outputs/androidTest-results/**"));
        assertTrue(workflow.contains("foundry-java-android/build/reports/androidTests/**"));
    }

    @Test
    void releaseContractsExcludeEveryProductionStartupFixture() throws IOException {
        String rootBuild = read("build.gradle.kts");
        int classStart = rootBuild.indexOf("val allowedBootstrapAndroidClasses =");
        int nativeStart = rootBuild.indexOf("val requiredAndroidNativeLibraries =", classStart);
        int nativeEnd = rootBuild.indexOf("val resolveLockTasks =", nativeStart);
        String classAllowlist = rootBuild.substring(classStart, nativeStart);
        String nativeAllowlist = rootBuild.substring(nativeStart, nativeEnd);
        String mainManifest = read("foundry-java-android/src/main/AndroidManifest.xml");
        String exports = read("foundry-java-android/src/main/cpp/foundry_java_exports.map");
        String consumerRules = read("foundry-java-android/src/main/consumer-rules.pro");
        String runtimeApi = read("foundry-java-runtime/api/foundry-java-runtime.api");

        for (String fixtureClass : DEBUG_FIXTURE_CLASSES) {
            assertFalse(classAllowlist.contains(fixtureClass), fixtureClass);
            assertFalse(exports.contains(fixtureClass), fixtureClass);
            assertFalse(consumerRules.contains(fixtureClass), fixtureClass);
            assertFalse(runtimeApi.contains(fixtureClass), fixtureClass);
        }
        assertEquals(
                """
                val requiredAndroidNativeLibraries =
                    setOf(
                        "jni/armeabi-v7a/libfoundry_java.so",
                        "jni/arm64-v8a/libfoundry_java.so",
                        "jni/x86/libfoundry_java.so",
                        "jni/x86_64/libfoundry_java.so",
                    )
                """
                        .trim(),
                nativeAllowlist.trim());
        assertFalse(nativeAllowlist.contains("libfoundry_java_test_host.so"));
        assertFalse(nativeAllowlist.contains("libfoundry_android.so"));
        assertEquals(
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />",
                mainManifest.trim());
        assertEquals(
                "7487bcff4a8ab4affd0ea43f4f19bbb7de4b556c6b4f769f2a7e5af6771f9633",
                sha256("foundry-java-android/src/main/cpp/foundry_java_exports.map"));
        assertEquals(
                "655945c7347bb4ba01a9d259c2b4eac67605c5bb2e70b5d74057e31a198f80f9",
                sha256("foundry-java-android/src/main/consumer-rules.pro"));
        assertEquals(
                "d32c0f45362426429d8e48871514792c4df7f512ca47909887fb9634ea29f8e4",
                sha256("foundry-java-runtime/api/foundry-java-runtime.api"));
    }

    @Test
    void theEngineApiParityOracleGatesEveryPullRequestWithUploadedEvidence() throws IOException {
        String runtimeBuild = read("foundry-java-runtime/build.gradle.kts");
        String workflow = read(".github/workflows/ci.yml");
        String accounting =
                read("foundry-java-runtime/api/foundry-java-realization-accounting.txt");
        String runtimeApi = read("foundry-java-runtime/api/foundry-java-runtime.api");
        String oracle =
                read(
                        "foundry-java-generator/src/main/java/games/cafecito/foundry/generator/"
                                + "RealizationOracle.java");
        String vocabulary =
                read(
                        "foundry-java-generator/src/main/java/games/cafecito/foundry/generator/"
                                + "NonRealizationReason.java");

        assertTrue(
                runtimeBuild.contains("tasks.register<JavaExec>(\"verifyGeneratedRealization\")"));
        assertTrue(runtimeBuild.contains("games.cafecito.foundry.generator.RealizationVerifier"));
        assertTrue(
                runtimeBuild.contains("api/foundry-java-realization-accounting.txt"),
                "the pinned per-entity accounting must gate generated realization");
        assertEquals(
                1,
                occurrences(
                        runtimeBuild,
                        """
                        tasks.named("check") {
                            dependsOn(
                                verifyRuntimeApi,
                                verifyGeneratedRealization,
                        """
                                .trim()));
        assertTrue(
                workflow.contains("foundry-java-runtime/build/reports/foundry-realization/**"),
                "the realization map and any diff must be uploaded as evidence");
        assertTrue(accounting.startsWith("foundry-java-realization-summary/1\n"));
        assertTrue(accounting.contains("realization-map-sha256 "));
        assertTrue(accounting.contains("source-entities 57899"));
        assertTrue(accounting.contains("realized-entities "));
        assertTrue(accounting.contains("non-realized-entities "));

        // The generated surface is accounted for per public root; the collapsed aggregate line
        // count
        // no longer stands in for that evidence, while one aggregate digest is retained.
        assertFalse(runtimeApi.contains("games.cafecito.foundry.generated|public-api-lines"));
        assertEquals(
                1, occurrences(runtimeApi, "games.cafecito.foundry.generated|public-api-sha256"));
        assertTrue(
                runtimeApi.contains(
                        "games.cafecito.foundry.generated.classes.Node|public-api-sha256"));
        assertTrue(
                runtimeApi.contains(
                        "games.cafecito.foundry.generated.classes.Node|public-api-lines"));

        // Approving a non-realization reason stays an explicit, reviewable vocabulary change.
        assertEquals(6, occurrences(vocabulary, "REALIZED_"));
        assertTrue(vocabulary.contains("Closed vocabulary"));
        assertTrue(oracle.contains("SUPPORTED_ENTITY_WITHOUT_REALIZED_MEMBER"));
        assertTrue(oracle.contains("GENERATED_MEMBER_WITHOUT_SOURCE_ENTITY"));
        assertTrue(oracle.contains("MANIFEST_CLASSIFICATION_DRIFT"));
        assertTrue(oracle.contains("UNAPPROVED_NON_REALIZATION_REASON"));
        assertFalse(oracle.contains("Foundry-Swift"));
    }

    @Test
    void lifecycleDocumentationSeparatesProviderPrimingFromNativeCore() throws IOException {
        String documentation =
                (read("docs/android-integration.md")
                                + read("docs/android.md")
                                + read("docs/architecture.md")
                                + read("docs/memory-and-threading.md"))
                        .replaceAll("\\s+", " ");

        for (String statement :
                List.of(
                        "Provider priming runs before `Application.onCreate()` and creates no "
                                + "binding context.",
                        "`foundry_java_library_init` and the native CORE callback create the "
                                + "production context.",
                        "Direct `FoundryJavaInitializer.initialize` is a compatibility and test "
                                + "entry only.",
                        "Registration follows the exact deterministic topological order.",
                        "Teardown unregisters in exact reverse topological order.",
                        "Bridge shutdown is process-terminal; restart requires a fresh Android "
                                + "process.")) {
            assertTrue(documentation.contains(statement), statement);
        }
    }

    @Test
    void nativeDispatchSourceOwnershipIsExplicitAndNonReflective() throws IOException {
        String generator =
                read(
                        "foundry-java-generator/src/main/java/games/cafecito/foundry/generator/"
                                + "FoundrySourceGenerator.java");
        String nativeEngine =
                read(
                        "foundry-java-android/src/main/java/games/cafecito/foundry/java/"
                                + "FoundryNativeEngine.java");

        assertTrue(generator.contains("GeneratedNativeDispatch.java"));
        assertTrue(generator.contains("GeneratedNativeDispatchShard"));
        assertTrue(
                Files.isRegularFile(
                        ROOT.resolve(
                                "foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/"
                                        + "FoundryNativeDispatch.java")));
        assertTrue(
                nativeEngine.contains(
                        "import games.cafecito.foundry.generated.GeneratedNativeDispatch;"));
        assertTrue(nativeEngine.contains("GeneratedNativeDispatch::require"));
        assertFalse(nativeEngine.contains("Class.forName"));
        assertFalse(nativeEngine.contains("getDeclaredMethod"));
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath));
    }

    private static String sha256(String relativePath) throws IOException {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(Files.readAllBytes(ROOT.resolve(relativePath))));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int occurrences(String value, String needle) {
        return value.split(Pattern.quote(needle), -1).length - 1;
    }

    private static boolean containsAndroidSourceDeclaration(String relativePath)
            throws IOException {
        try (var paths = Files.walk(ROOT.resolve(relativePath))) {
            Path sourceRoot = ROOT.resolve(relativePath).resolve("src");
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.startsWith(sourceRoot))
                    .filter(
                            path ->
                                    path.getFileName().toString().endsWith(".java")
                                            || path.getFileName().toString().endsWith(".kt"))
                    .map(
                            path -> {
                                try {
                                    return Files.readString(path);
                                } catch (IOException exception) {
                                    throw new IllegalStateException(exception);
                                }
                            })
                    .anyMatch(RepositoryContractTest::containsAndroidDeclaration);
        }
    }

    private static String readTree(String relativePath) throws IOException {
        try (var paths = Files.walk(ROOT.resolve(relativePath))) {
            Path sourceRoot = ROOT.resolve(relativePath).resolve("src");
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.startsWith(sourceRoot))
                    .map(
                            path -> {
                                try {
                                    return Files.readString(path);
                                } catch (IOException exception) {
                                    throw new IllegalStateException(exception);
                                }
                            })
                    .reduce("", String::concat);
        }
    }

    private static boolean containsAndroidDeclaration(String source) {
        return Pattern.compile(
                        "(?m)^\\s*(?:package|import)(?:\\s+static)?"
                                + "\\s+android(?:\\.|\\s*(?:;|$))")
                .matcher(withoutCommentsAndLiterals(source))
                .find();
    }

    private static String withoutCommentsAndLiterals(String source) {
        StringBuilder sanitized = new StringBuilder(source.length());
        boolean inBlockComment = false;
        boolean inLineComment = false;
        boolean inString = false;
        boolean inCharacter = false;
        boolean inTripleQuotedString = false;
        boolean escaped = false;

        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

            if (inLineComment) {
                if (current == '\n') {
                    inLineComment = false;
                    sanitized.append('\n');
                } else {
                    sanitized.append(' ');
                }
            } else if (inBlockComment) {
                if (current == '*' && next == '/') {
                    sanitized.append("  ");
                    index++;
                    inBlockComment = false;
                } else {
                    sanitized.append(current == '\n' ? '\n' : ' ');
                }
            } else if (inTripleQuotedString) {
                if (source.startsWith("\"\"\"", index)) {
                    sanitized.append("   ");
                    index += 2;
                    inTripleQuotedString = false;
                } else {
                    sanitized.append(current == '\n' ? '\n' : ' ');
                }
            } else if (inString || inCharacter) {
                sanitized.append(current == '\n' ? '\n' : ' ');
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if ((inString && current == '"') || (inCharacter && current == '\'')) {
                    inString = false;
                    inCharacter = false;
                }
            } else if (current == '/' && next == '/') {
                sanitized.append("  ");
                index++;
                inLineComment = true;
            } else if (current == '/' && next == '*') {
                sanitized.append("  ");
                index++;
                inBlockComment = true;
            } else if (source.startsWith("\"\"\"", index)) {
                sanitized.append("   ");
                index += 2;
                inTripleQuotedString = true;
            } else if (current == '"') {
                sanitized.append(' ');
                inString = true;
            } else if (current == '\'') {
                sanitized.append(' ');
                inCharacter = true;
            } else {
                sanitized.append(current);
            }
        }
        return sanitized.toString();
    }
}
