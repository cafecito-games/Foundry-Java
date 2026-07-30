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

    private static int occurrences(String value, String needle) {
        return value.split(Pattern.quote(needle), -1).length - 1;
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath));
    }
}
