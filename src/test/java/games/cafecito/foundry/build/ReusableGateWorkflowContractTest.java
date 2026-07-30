package games.cafecito.foundry.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ReusableGateWorkflowContractTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final String SHARED = ".github/workflows/gates.yml";
    private static final String CALL = "uses: ./.github/workflows/gates.yml";

    @Test
    void sharedGatesCannotRequestSecretsOrProtectedEnvironments() throws IOException {
        String shared = read(SHARED);

        assertNoYamlKey(shared, "secrets");
        assertFalse(shared.contains("secrets: inherit"));
        assertNoYamlKey(shared, "environment");
    }

    @Test
    void callersDoNotForwardSecretsToSharedGates() throws IOException {
        String ci = read(".github/workflows/ci.yml");
        String release = read(".github/workflows/release.yml");

        String ciCheck = workflowJob(ci, "check");
        int releaseStage = release.indexOf("  stage:\n");
        assertTrue(releaseStage > 0);
        String releaseGates = workflowJob(release.substring(0, releaseStage), "gates");

        assertNoYamlKey(ciCheck, "secrets");
        assertFalse(ciCheck.contains("secrets: inherit"));
        assertNoYamlKey(releaseGates, "secrets");
        assertFalse(releaseGates.contains("secrets: inherit"));
    }

    @Test
    void enginePinDocumentationAssignsTheCacheKeyToSharedGates() throws IOException {
        String documentation = read("docs/engine-pin.md");

        assertTrue(documentation.contains(".github/workflows/gates.yml"));
        assertFalse(documentation.contains(".github/workflows/ci.yml"));
    }

    @Test
    void ciAndReleaseCallOneSharedHostAndDeviceGateWorkflow() throws IOException {
        String shared = read(SHARED);
        String ci = read(".github/workflows/ci.yml");
        String release = read(".github/workflows/release.yml");

        assertTrue(shared.contains("on:\n  workflow_call:"));
        assertBooleanInput(shared, "release");
        assertBooleanInput(shared, "dry_run");
        assertTrue(shared.contains("  host-gate:\n"));
        assertTrue(shared.contains("  device-gate:\n"));
        int hostGateStart = shared.indexOf("  host-gate:\n");
        int deviceGateStart = shared.indexOf("  device-gate:\n", hostGateStart);
        assertTrue(hostGateStart > 0 && deviceGateStart > hostGateStart);
        String hostGate = shared.substring(hostGateStart, deviceGateStart);
        String checkoutMarker = "- uses: actions/checkout@";
        int checkoutStart = hostGate.indexOf(checkoutMarker);
        int checkoutEnd = hostGate.indexOf("\n      - ", checkoutStart + checkoutMarker.length());
        assertTrue(checkoutStart >= 0 && checkoutEnd > checkoutStart);
        String checkoutStep = hostGate.substring(checkoutStart, checkoutEnd);
        assertTrue(
                checkoutStep.startsWith(
                        "- uses: actions/checkout@"
                                + "3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1\n"));
        String releaseFetchDepth = "fetch-depth: ${{ inputs.release && '0' || '1' }}";
        assertTrue(checkoutStep.contains("with:\n          " + releaseFetchDepth));
        assertEquals(1, occurrences(shared, releaseFetchDepth));

        assertEquals(1, occurrences(ci, CALL));
        assertEquals(1, occurrences(release, CALL));
        assertFalse(ci.contains("release: true"));
        assertTrue(
                release.contains(
                        "with:\n"
                                + "      release: true\n"
                                + "      dry_run: ${{ inputs.dry_run }}"));
        assertTrue(release.contains("needs: [gates]"));

        assertFalse(ci.contains("\n  host-gate:\n"));
        assertFalse(ci.contains("\n  device-gate:\n"));
        assertFalse(release.contains("\n  host-gate:\n"));
        assertFalse(release.contains("\n  device-gate:\n"));
        assertFalse(ci.contains("android-actions/setup-android@"));
        int stage = release.indexOf("  stage:\n");
        assertTrue(stage > 0);
        String releaseBeforeStage = release.substring(0, stage);
        assertFalse(releaseBeforeStage.contains("android-actions/setup-android@"));

        assertOwnedOnlyByShared(
                shared, ci, release, "bash gradle/verify-configuration-cache-reuse.sh");
        assertOwnedOnlyByShared(
                shared, ci, release, "bash gradle/run-samples-conformance-matrix.sh");
        assertOwnedOnlyByShared(
                shared, ci, release, "bash gradle/run-engine-loaded-conformance-gate.sh");
        assertOwnedOnlyByShared(
                shared, ci, release, "- name: Create and launch the API 36 emulator");
    }

    @Test
    void onlySafePullRequestsSkipTheEngineLoadedDeviceGate() throws IOException {
        String shared = read(SHARED);
        String ci = read(".github/workflows/ci.yml");
        String release = read(".github/workflows/release.yml");
        String device = workflowJob(shared, "device-gate");

        for (String workflow : new String[] {shared, ci, release}) {
            String permissions = workflowPermissions(workflow);
            assertEquals(
                    1,
                    occurrences(permissions, "  contents: read\n"),
                    "each workflow must grant read-only repository contents access");
            assertEquals(
                    1,
                    occurrences(permissions, "  pull-requests: read\n"),
                    "each workflow must grant read-only pull request metadata access");
            assertEquals(
                    0,
                    occurrences(workflow, "pull-requests: write"),
                    "pull request metadata access must never be writable");
        }

        String scope =
                workflowStep(device, "Classify whether this change needs the engine-loaded gate");
        assertTrue(scope.contains("\n        id: engine-gate-scope\n"));
        assertTrue(scope.contains("\n          GH_TOKEN: ${{ github.token }}\n"));
        assertTrue(
                scope.contains("\n          PR_NUMBER: ${{ github.event.pull_request.number }}\n"));
        assertTrue(
                scope.contains(
                        "\n          EXPECTED_CHANGED_FILES:"
                                + " ${{ github.event.pull_request.changed_files }}\n"));
        assertTrue(scope.contains("gh api --paginate --slurp"));
        assertTrue(
                scope.contains("repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}/files?per_page=100"));
        assertTrue(scope.contains("[.[][]] | length"));
        assertTrue(
                scope.contains(
                        "if [[ \"$observed_count\" != \"$EXPECTED_CHANGED_FILES\" ]]; then\n"
                                + "            select_gate true classification-incomplete\n"
                                + "            exit 0\n"
                                + "          fi"));
        assertTrue(scope.contains("(.filename, .previous_filename?)"));
        assertTrue(scope.contains("bash gradle/classify-engine-gate-paths.sh"));
        for (String failedCommand :
                new String[] {
                    "if ! pages=\"$(",
                    "if ! observed_count=\"$(",
                    "if ! paths=\"$(",
                    "if ! decision=\"$("
                }) {
            assertTrue(scope.contains(failedCommand), failedCommand + " must fail closed");
        }
        assertEquals(
                4,
                occurrences(
                        scope,
                        ")\"; then\n"
                                + "            select_gate true classification-failed\n"
                                + "            exit 0\n"
                                + "          fi"),
                "every API, parse, and classifier command failure must fail closed");
        assertEquals(
                5,
                occurrences(scope, "select_gate true classification-failed"),
                "all five classification failures must run the engine gate");
        assertEquals(
                1,
                occurrences(scope, "select_gate true classification-incomplete"),
                "only the changed-file count mismatch may be classification-incomplete");
        assertTrue(
                scope.contains(
                        "$'run=false\\nreason=safe-only')\n"
                                + "              select_gate false safe-only\n"
                                + "              ;;"));
        assertTrue(
                scope.contains(
                        "$'run=true\\nreason=relevant')\n"
                                + "              select_gate true relevant\n"
                                + "              ;;"));
        assertTrue(
                scope.contains(
                        "$'run=true\\nreason=fail-closed')\n"
                                + "              select_gate true fail-closed\n"
                                + "              ;;"));
        assertTrue(
                scope.contains(
                        "*)\n"
                                + "              select_gate true classification-failed\n"
                                + "              ;;"));

        String checkout = workflowUsesStep(device, "actions/checkout@");
        assertFalse(checkout.contains("\n        if:"));
        assertFalse(checkout.contains("engine-gate-scope"));
        assertEquals(
                device.indexOf(checkout) + checkout.length() + 1,
                device.indexOf(scope),
                "the classifier must be the immediate next step after device checkout");

        for (String action :
                new String[] {
                    "actions/setup-java@", "android-actions/setup-android@", "actions/cache@"
                }) {
            String actionStep = workflowUsesStep(device, action);
            assertFalse(
                    actionStep.contains("\n        if:"), action + " must remain unconditional");
            assertFalse(actionStep.contains("engine-gate-scope"));
        }
        String setupGradle = workflowUsesStep(device, "gradle/actions/setup-gradle@");
        assertEquals(1, occurrences(setupGradle, "\n        if: ${{ !inputs.release }}\n"));
        assertEquals(1, occurrences(setupGradle, "\n        if:"));
        assertFalse(setupGradle.contains("engine-gate-scope"));

        String condition = "if: steps.engine-gate-scope.outputs.run == 'true'";
        assertEquals(
                1,
                occurrences(device, condition),
                "only the engine-loaded gate may depend on the classifier");
        String engine = workflowStep(device, "Run the engine-loaded API 36 conformance gate");
        assertTrue(
                engine.contains("\n        " + condition + "\n"),
                "the engine-loaded gate must use the classifier output");

        for (String step :
                new String[] {
                    "Create and launch the API 36 emulator",
                    "Wait for observable emulator boot",
                    "Run production startup twice in fresh processes",
                    "Run the Java and Kotlin conformance matrix as consumer samples",
                    "Upload API 36 production startup evidence",
                    "Upload device gate evidence"
                }) {
            String namedStep = workflowStep(device, step);
            assertFalse(
                    namedStep.contains("engine-gate-scope"),
                    step + " must remain independent of the engine-loaded gate classifier");
            if (step.equals("Upload API 36 production startup evidence")) {
                assertEquals(
                        1,
                        occurrences(
                                namedStep, "\n        if: ${{ always() && !inputs.release }}\n"));
                assertEquals(1, occurrences(namedStep, "\n        if:"));
            } else if (step.equals("Upload device gate evidence")) {
                assertEquals(
                        1, occurrences(namedStep, "\n        if: always() && inputs.release\n"));
                assertEquals(1, occurrences(namedStep, "\n        if:"));
            } else {
                assertFalse(namedStep.contains("\n        if:"), step + " must be unconditional");
            }
        }
    }

    private static void assertBooleanInput(String workflow, String input) {
        Pattern inputBlock =
                Pattern.compile(
                        "(?m)^      "
                                + Pattern.quote(input)
                                + ":\\n(?:        .*\\n)*        type: boolean$");
        assertTrue(inputBlock.matcher(workflow).find(), input + " must be a boolean input");
    }

    private static void assertOwnedOnlyByShared(
            String shared, String ci, String release, String marker) {
        assertEquals(1, occurrences(shared, marker), marker + " must appear once in shared gates");
        assertEquals(0, occurrences(ci, marker), marker + " must not remain in CI");
        assertEquals(0, occurrences(release, marker), marker + " must not remain in release");
    }

    private static void assertNoYamlKey(String workflowSection, String key) {
        Pattern declaration = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + ":");
        assertFalse(
                declaration.matcher(workflowSection).find(),
                key + " must not be declared in this workflow section");
    }

    private static String workflowJob(String workflow, String name) {
        String marker = "  " + name + ":\n";
        assertEquals(1, occurrences(workflow, marker), name + " must identify one workflow job");
        int start = workflow.indexOf(marker);
        Pattern nextJob = Pattern.compile("(?m)^  [A-Za-z0-9_-]+:\\s*$");
        var matcher = nextJob.matcher(workflow);
        int end = matcher.find(start + marker.length()) ? matcher.start() : workflow.length();
        return workflow.substring(start, end);
    }

    private static String workflowPermissions(String workflow) {
        String marker = "\npermissions:\n";
        assertEquals(
                1,
                occurrences(workflow, marker),
                "the workflow must declare one top-level permissions block");
        int start = workflow.indexOf(marker) + 1;
        int end = workflow.indexOf("\n\n", start + marker.length() - 1);
        assertTrue(end > start, "the top-level permissions block must terminate");
        return workflow.substring(start, end + 1);
    }

    private static String workflowStep(String job, String name) {
        String marker = "      - name: " + name + "\n";
        assertEquals(1, occurrences(job, marker), name + " must identify one workflow step");
        int start = job.indexOf(marker);
        int end = job.indexOf("\n      - ", start + marker.length());
        return job.substring(start, end < 0 ? job.length() : end);
    }

    private static String workflowUsesStep(String job, String usesMarker) {
        String marker = "      - uses: " + usesMarker;
        assertEquals(
                1,
                occurrences(job, marker),
                usesMarker + " must identify one workflow action step");
        int start = job.indexOf(marker);
        int end = job.indexOf("\n      - ", start + marker.length());
        return job.substring(start, end < 0 ? job.length() : end);
    }

    private static int occurrences(String value, String needle) {
        return value.split(Pattern.quote(needle), -1).length - 1;
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath));
    }
}
