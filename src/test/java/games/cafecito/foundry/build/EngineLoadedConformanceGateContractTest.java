package games.cafecito.foundry.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Contract for the engine-loaded API 36 conformance gate.
 *
 * <p>The gate itself can only run where a real Foundry editor, the pinned export templates, and an
 * API 36 emulator are all available, so this test owns everything about it that is verifiable
 * without them: that the engine release is pinned by tag and digest, that the pin agrees with the
 * vendored API identity, that the harness verifies every digest before use even on a cache hit,
 * that the harness proves engine-loaded behaviour rather than packaging, and that the negative
 * self-test against a binding whose registration is disabled is wired and required to fail.
 */
class EngineLoadedConformanceGateContractTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final String RELEASE_TAG = "v0.1.0-alpha.14";
    private static final String PRODUCER_COMMIT = "b9a5e66c21f8f7b707a9e526ca20557485c53227";
    private static final String TEMPLATE_DIGEST =
            "6a5cc2bb5b8b4cc7f48bcdf51575645fca408ac62e25dad0691d71f3a117a03f";
    private static final String RUNTIME_MARKER = "FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_READY";
    private static final String LOAD_FAILED_TOKEN = "FOUNDRY_JAVA_PLATFORM_EXTENSION_LOAD_FAILED";
    private static final List<String> ABIS = List.of("arm64-v8a", "armeabi-v7a", "x86", "x86_64");
    private static final List<String> SCENARIOS =
            List.of("default-debug", "custom-debug", "default-release", "custom-release");

    @Test
    void theEnginePinAgreesWithTheVendoredApiIdentity() throws IOException {
        String pin = read("gradle/engine-pin.json");
        String provenance = read("api/current/provenance.json");

        assertEquals(RELEASE_TAG, jsonString(pin, "release_tag"));
        assertEquals(RELEASE_TAG, jsonString(provenance, "source_release"));
        assertEquals(PRODUCER_COMMIT, jsonString(pin, "producer_commit"));
        assertEquals(PRODUCER_COMMIT, jsonString(provenance, "foundry_commit"));
        assertEquals(jsonString(provenance, "api_version"), jsonString(pin, "api_version"));
        // The pinned release must be the same publication the binding was generated from, so the
        // API archive digest recorded in the pin has to be the vendored archive digest.
        assertTrue(
                pin.contains(jsonString(provenance, "archive_sha256")),
                "the pin must record the vendored API archive digest");
        assertTrue(pin.contains(TEMPLATE_DIGEST), "the export template digest must be pinned");
        assertTrue(
                pin.contains("\"source_repository\": \"https://github.com/cafecito-games/Foundry"));
        assertTrue(pin.contains(PRODUCER_COMMIT + "\""));
        assertTrue(pin.contains("\"sparse_directory\": \"platform/android\""));
        for (String asset : List.of("editor", "export_templates", "api")) {
            assertTrue(pin.contains("\"" + asset + "\": {"), asset + " must be pinned");
        }
        for (Matcher matcher = Pattern.compile("\"sha256\": \"([^\"]*)\"").matcher(pin);
                matcher.find(); ) {
            assertTrue(
                    Pattern.matches("[0-9a-f]{64}", matcher.group(1)),
                    "every pinned digest must be a lowercase SHA-256");
        }
        for (Matcher matcher = Pattern.compile("\"size\": ([0-9]+)").matcher(pin);
                matcher.find(); ) {
            assertTrue(Long.parseLong(matcher.group(1)) > 0, "every pinned size must be positive");
        }
    }

    @Test
    void theEngineFetcherVerifiesEveryDigestBeforeUseIncludingOnACacheHit() throws IOException {
        String fetcher = read("gradle/fetch-pinned-engine.sh");

        assertTrue(fetcher.contains("set -euo pipefail"));
        assertTrue(fetcher.contains("gradle/engine-pin.json"));
        // A cache hit is the dangerous path: the archive is roughly 1.1 GB, so it is cached by tag
        // and digest, and the digest is still recomputed before the archive is opened.
        assertTrue(fetcher.contains("verify_digest"));
        assertTrue(
                fetcher.contains("cache_hit"),
                "the fetcher must distinguish a cache hit from a download");
        assertTrue(
                fetcher.contains("digest mismatch"),
                "a digest mismatch must be reported as such and fail");
        assertTrue(
                fetcher.contains("expected_size"),
                "a pinned size must be checked alongside the digest");
        assertTrue(fetcher.contains("android_source.zip"));
        // The upstream device tool is not one file: its verify-apks path loads a sibling module and
        // reads the engine's own native sources, so the whole pinned upstream directory has to be
        // materialized at its real repository paths.
        assertTrue(read("gradle/engine-pin.json").contains("android_device_acceptance.py"));
        assertTrue(fetcher.contains("${engine_checkout}/${acceptance_path}"));
        assertTrue(fetcher.contains("sparse-checkout"));
        assertTrue(fetcher.contains("$sparse_directory"));
        assertTrue(
                fetcher.contains("rev-parse HEAD"),
                "the checked-out revision must be asserted to be the pinned commit");
        assertFalse(
                fetcher.contains("--insecure") || fetcher.contains("-k "),
                "downloads must not weaken transport verification");
        assertTrue(fetcher.contains("engine-manifest.json"));
    }

    @Test
    void theExportedPayloadInspectorCoversEveryAbiAndTheForbiddenHostLibrary() throws IOException {
        String inspector = read("gradle/verify-exported-abi-payloads.sh");
        String symbols = read("gradle/foundry-java-bridge-symbols.txt");
        String bridgeVerifier = read("gradle/verify-native-bridge.sh");

        assertTrue(inspector.contains("set -euo pipefail"));
        for (String abi : ABIS) {
            assertTrue(inspector.contains(abi), abi + " must be inspected");
        }
        assertTrue(inspector.contains("llvm-readelf"));
        assertTrue(inspector.contains("assets/FoundryJava.foundryextension"));
        assertTrue(inspector.contains("assets/foundry_java/registry-index-v2.txt"));
        assertTrue(inspector.contains("libfoundry_android.so"));
        assertTrue(inspector.contains("libjvm"));
        assertTrue(inspector.contains("exactly one"));
        assertTrue(inspector.contains("keep"), "minification keep rules must be inspected");
        assertFalse(
                inspector.contains("|| true\nexit 0"),
                "the inspector must not swallow its own failures");

        // The exported bridge surface the AAR verifier enforces and the surface the exported APK
        // is inspected against are the same surface, so they are compared here and can never drift.
        assertEquals(21, symbols.lines().filter(line -> !line.isBlank()).count());
        assertEquals(
                symbols.lines().filter(line -> !line.isBlank()).sorted().toList(),
                quotedSymbols(bridgeVerifier).stream().sorted().toList(),
                "the shared bridge symbol list must match gradle/verify-native-bridge.sh");
        assertTrue(inspector.contains("foundry-java-bridge-symbols.txt"));
    }

    @Test
    void theGateProvesEngineLoadedBehaviourAcrossTheFourApplicationCombinations()
            throws IOException {
        String gate = read("gradle/run-engine-loaded-conformance-gate.sh");

        assertTrue(gate.contains("set -euo pipefail"));
        assertTrue(gate.contains("${1:-emulator-5554}"));
        assertTrue(gate.contains("ro.build.version.sdk"));
        assertTrue(gate.contains("requires API 36"));
        // The deep device assertions are upstream property. The gate calls them; it never forks or
        // vendors them.
        assertTrue(gate.contains("android_device_acceptance.py"));
        assertTrue(gate.contains("verify-apks"));
        assertTrue(gate.contains("--required-runtime-marker"));
        assertTrue(gate.contains(RUNTIME_MARKER));
        assertTrue(gate.contains(LOAD_FAILED_TOKEN));
        assertTrue(gate.contains("bash gradle/fetch-pinned-engine.sh"));
        assertTrue(gate.contains("bash gradle/verify-exported-abi-payloads.sh"));
        for (String scenario : SCENARIOS) {
            assertTrue(gate.contains(scenario), scenario + " must be exported");
        }
        assertTrue(gate.contains("games.cafecito.foundry.game"), "the default application ID");
        assertTrue(gate.contains("dev.example.foundryjava"), "a custom application ID");
        assertTrue(gate.contains("--mode"), "the export mode must be explicit");
        assertTrue(
                gate.contains("\"$mode\" == \"release\""), "a minified release must be exported");
        assertTrue(gate.contains("mapping.txt"), "minified releases must be checked");
        // Artifacts under test are always built here, never resolved from a published release of
        // this repository.
        assertTrue(gate.contains("./gradlew") || gate.contains("gradle="));
        assertFalse(
                gate.contains("mavenCentral") || gate.contains("foundry-java-android:0."),
                "the gate must never consume a published Foundry-Java release");
        assertTrue(gate.contains("summary.json"));
        assertTrue(gate.contains("--process-timeout"));
    }

    @Test
    void theGateIsSelfTestedAgainstABindingWhoseRegistrationIsDisabled() throws IOException {
        String gate = read("gradle/run-engine-loaded-conformance-gate.sh");
        String acceptanceBuild = read("acceptance/extension/build.gradle.kts");
        String registered =
                read(
                        "acceptance/extension/src/registered/java/games/cafecito/foundry/"
                                + "acceptance/EngineProbe.java");
        String unregistered =
                read(
                        "acceptance/extension/src/unregistered/java/games/cafecito/foundry/"
                                + "acceptance/EngineProbe.java");

        // The negative proof is the point of the gate: a binding that is packaged perfectly but
        // whose engine class never registers must fail. The fixture pair differs only in the
        // registered engine class name, so packaging, ABIs, descriptor, and registry index stay
        // byte-for-byte comparable and only the ClassDB resolution changes.
        assertTrue(gate.contains("self-test"));
        assertTrue(
                gate.contains("foundryJavaRegistrationDisabled"),
                "the disabled-registration fixture must be selectable");
        assertTrue(
                gate.contains("unexpectedly passed"),
                "a passing self-test run must be reported as a gate failure");
        // Upstream reports a live process that never emitted the marker as a timed-out marker wait,
        // so accepting only the other diagnostic would reject the negative proof it exists to make.
        assertTrue(gate.contains("did not log required runtime marker"));
        assertTrue(gate.contains("waiting for required runtime marker .* timed out"));
        assertTrue(
                gate.contains("${failure_marker} class_missing"),
                "the self-test must observe the engine-loaded script rejecting the missing class");
        assertTrue(acceptanceBuild.contains("foundryJavaRegistrationDisabled"));
        assertTrue(acceptanceBuild.contains("src/registered/java"));
        assertTrue(acceptanceBuild.contains("src/unregistered/java"));
        assertTrue(acceptanceBuild.contains("-Afoundry.module=acceptance"));
        assertTrue(registered.contains("name = \"FoundryJavaEngineProbe\""));
        assertTrue(unregistered.contains("name = \"FoundryJavaEngineProbeDisabled\""));
        assertEquals(
                registered.replace("FoundryJavaEngineProbeDisabled", "FoundryJavaEngineProbe"),
                unregistered.replace("FoundryJavaEngineProbeDisabled", "FoundryJavaEngineProbe"),
                "the fixtures must differ only in the registered engine class name");
    }

    @Test
    void theAcceptanceProjectResolvesTheJavaClassThroughClassDatabase() throws IOException {
        String script = read("acceptance/project/main.fs");
        String scene = read("acceptance/project/main.tscn");
        String project = read("acceptance/project/project.foundry");
        String settings = read("acceptance/settings.gradle.kts");
        String rootSettings = read("settings.gradle.kts");

        assertTrue(script.contains("ClassDB.class_exists(\"FoundryJavaEngineProbe\")"));
        assertTrue(script.contains("ClassDB.instantiate(\"FoundryJavaEngineProbe\")"));
        assertTrue(script.contains("engine_probe"), "a Java-defined method must be dispatched");
        assertTrue(
                script.indexOf(RUNTIME_MARKER) > script.indexOf("ClassDB.instantiate"),
                "the marker may only be printed after the Java class answered");
        assertEquals(1, occurrences(script, "print(\"" + RUNTIME_MARKER + "\")"));
        assertTrue(script.contains("FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_FAILED"));
        assertTrue(scene.contains("res://main.fs"));
        assertTrue(project.contains("run/main_scene=\"res://main.tscn\""));

        // The acceptance build is a consumer build, exactly like the samples: never a Foundry-Java
        // subproject.
        assertFalse(rootSettings.contains("acceptance"));
        assertTrue(settings.contains("\":extension\""));
        assertFalse(settings.contains("includeBuild"));
    }

    @Test
    void continuousIntegrationRunsTheGateOnTheApi36EmulatorAndKeepsItsEvidence()
            throws IOException {
        String workflow = read(".github/workflows/gates.yml");
        String ci = read(".github/workflows/ci.yml");
        String release = read(".github/workflows/release.yml");

        assertTrue(workflow.contains("bash gradle/run-engine-loaded-conformance-gate.sh"));
        // The 1.1 GB export template archive is cached by release tag and digest, so the key must
        // contain both and can never be reused across a bump.
        assertTrue(workflow.contains("foundry-engine-" + RELEASE_TAG + "-" + TEMPLATE_DIGEST));
        assertTrue(workflow.contains("FOUNDRY_ENGINE_CACHE"));
        assertTrue(workflow.contains("${{ runner.temp }}/foundry-java-engine-gate/**"));
        assertTrue(workflow.contains("acceptance/*/build/reports/**"));
        int gateStep = workflow.indexOf("- name: Run the engine-loaded API 36 conformance gate");
        int matrixStep =
                workflow.indexOf("- name: Run the Java and Kotlin conformance matrix as consumer");
        assertTrue(gateStep > 0 && matrixStep > 0);
        assertTrue(gateStep > matrixStep, "the engine gate runs after the cheaper device legs");

        int deviceJob = workflow.indexOf("  device-gate:\n");
        assertTrue(deviceJob > 0);
        String checkoutMarker = "      - uses: actions/checkout@";
        int checkoutStart = workflow.indexOf(checkoutMarker, deviceJob);
        int checkoutEnd = workflow.indexOf("\n      - ", checkoutStart + checkoutMarker.length());
        assertTrue(checkoutStart > deviceJob && checkoutEnd > checkoutStart);
        String scopeMarker =
                "      - name: Classify whether this change needs the engine-loaded gate\n";
        int scopeStart = workflow.indexOf(scopeMarker);
        assertTrue(scopeStart > 0, "the device job must classify the pull request scope");
        assertEquals(
                checkoutEnd + 1,
                scopeStart,
                "the classifier must be the immediate next device step after checkout");
        int scopeEnd = workflow.indexOf("\n      - ", scopeStart + scopeMarker.length());
        String scopeStep = workflow.substring(scopeStart, scopeEnd);
        assertTrue(scopeStep.contains("\n        id: engine-gate-scope\n"));
        assertTrue(
                scopeStep.contains(
                        "if [[ \"${GITHUB_EVENT_NAME}\" != \"pull_request\" ]]; then\n"
                                + "            select_gate true non-pull-request\n"
                                + "            exit 0\n"
                                + "          fi"));

        int gateStepEnd = workflow.indexOf("\n      - ", gateStep + 1);
        String engineStep = workflow.substring(gateStep, gateStepEnd);
        assertTrue(
                engineStep.startsWith(
                        "- name: Run the engine-loaded API 36 conformance gate\n"
                                + "        if: steps.engine-gate-scope.outputs.run == 'true'\n"
                                + "        shell: bash\n"
                                + "        run: bash"
                                + " gradle/run-engine-loaded-conformance-gate.sh"),
                "the engine step condition must immediately precede its shell and run command");

        assertTrue(ci.contains("  push:\n    branches: [main]\n"));
        assertTrue(ci.contains("  pull_request:\n"));
        assertTrue(release.contains("  push:\n    tags:\n"));
        assertTrue(release.contains("  workflow_dispatch:\n"));
        assertFalse(release.contains("  pull_request:\n"));
    }

    @Test
    void theEnginePinIsDocumentedWithABumpAndLocalReproductionProcedure() throws IOException {
        String documentation = read("docs/engine-pin.md");
        String compatibility = read("docs/api-compatibility.md");

        assertTrue(documentation.contains(RELEASE_TAG));
        assertTrue(documentation.contains(PRODUCER_COMMIT));
        assertTrue(documentation.contains(TEMPLATE_DIGEST));
        assertTrue(documentation.contains("gradle/engine-pin.json"));
        assertTrue(documentation.contains("gradle/run-engine-loaded-conformance-gate.sh"));
        assertTrue(documentation.contains("## Bumping the pin"));
        assertTrue(documentation.contains("## Reproducing the gate locally"));
        assertTrue(documentation.contains("api/current/provenance.json"));
        assertTrue(documentation.contains(RUNTIME_MARKER));
        assertTrue(documentation.contains("libfoundry_android.so"));
        assertTrue(compatibility.contains("engine-pin.md"));
    }

    @Test
    void selectiveEngineGateCadenceIsDocumented() throws IOException {
        String documentation = read("docs/engine-pin.md");
        String cadence =
                markdownSection(documentation, "## When the gate runs", "## Bumping the pin");
        String normalizedCadence = cadence.replaceAll("\\s+", " ");
        String normalizedReleasing = read("docs/releasing.md").replaceAll("\\s+", " ");

        assertTrue(normalizedCadence.contains("## When the gate runs"));
        assertTrue(normalizedCadence.contains("safe-to-skip"));
        assertTrue(normalizedCadence.contains("all changed files"));
        assertTrue(normalizedCadence.contains("Every push to `main`"));
        assertTrue(normalizedCadence.contains("every release"));
        assertTrue(normalizedCadence.contains("release dry-run"));
        assertTrue(normalizedCadence.contains("unknown path"));
        assertTrue(normalizedCadence.contains("gradle/extract-engine-gate-paths.sh"));
        assertTrue(normalizedCadence.contains("gradle/classify-engine-gate-paths.sh"));
        assertTrue(
                normalizedCadence.contains("current and previous paths"),
                "renames must be documented as checking both path identities");
        for (String safeCategory :
                List.of(
                        "documentation",
                        "Markdown",
                        "branding assets",
                        "issue or pull-request templates",
                        "`src/test`",
                        "`src/testFixtures`",
                        "`gradle/testFixtures`")) {
            assertTrue(
                    normalizedCadence.contains(safeCategory),
                    safeCategory + " must remain in the documented safe-to-skip set");
        }
        for (String alwaysOn :
                List.of(
                        "API 36 emulator",
                        "production startup",
                        "Java/Kotlin consumer matrix",
                        "engine-loaded step is skipped")) {
            assertTrue(
                    normalizedCadence.contains(alwaysOn),
                    alwaysOn + " must remain in the documented selective cadence");
        }
        assertTrue(normalizedCadence.contains("mixed change"));
        assertTrue(normalizedCadence.contains("runs the gate"));
        for (String failClosedDetail :
                List.of(
                        "Collection",
                        "classification errors",
                        "incomplete API response",
                        "malformed metadata",
                        "unknown GitHub file status",
                        "fail closed")) {
            assertTrue(
                    normalizedCadence.contains(failClosedDetail),
                    failClosedDetail + " must remain in the documented fail-closed policy");
        }
        assertTrue(
                normalizedCadence.contains("exact changed-file count"),
                "the extractor's exact count validation must be documented");
        for (String status :
                List.of(
                        "added",
                        "removed",
                        "modified",
                        "renamed",
                        "copied",
                        "changed",
                        "unchanged")) {
            assertTrue(
                    normalizedCadence.contains("`" + status + "`"),
                    status + " must remain in the documented GitHub status allowlist");
        }
        assertTrue(normalizedReleasing.contains("always selects the engine-loaded gate"));
        assertTrue(normalizedReleasing.contains("pull-request-only optimization"));
        assertTrue(normalizedReleasing.contains("cannot skip release verification"));
    }

    private static String markdownSection(
            String documentation, String startHeading, String endHeading) {
        int start = documentation.indexOf(startHeading);
        int end = documentation.indexOf(endHeading);
        assertTrue(start >= 0, startHeading + " must be present");
        assertTrue(end >= 0, endHeading + " must be present");
        assertTrue(end > start, endHeading + " must follow " + startHeading);
        return documentation.substring(start, end);
    }

    private static List<String> quotedSymbols(String script) {
        var symbols = new java.util.ArrayList<String>();
        Matcher matcher =
                Pattern.compile("'((?:JNI_On|Java_|foundry_java_)[A-Za-z0-9_]+)'").matcher(script);
        while (matcher.find()) {
            symbols.add(matcher.group(1));
        }
        return List.copyOf(symbols);
    }

    private static int occurrences(String value, String needle) {
        return value.split(Pattern.quote(needle), -1).length - 1;
    }

    private static String jsonString(String document, String key) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                        .matcher(document);
        assertTrue(matcher.find(), key + " must be present");
        return matcher.group(1);
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath));
    }
}
