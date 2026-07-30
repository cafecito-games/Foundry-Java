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
    void ciAndReleaseCallOneSharedHostAndDeviceGateWorkflow() throws IOException {
        String shared = read(SHARED);
        String ci = read(".github/workflows/ci.yml");
        String release = read(".github/workflows/release.yml");

        assertTrue(shared.contains("on:\n  workflow_call:"));
        assertTrue(shared.contains("      release:\n"));
        assertTrue(shared.contains("        type: boolean"));
        assertTrue(shared.contains("      dry_run:\n"));
        assertTrue(shared.contains("  host-gate:\n"));
        assertTrue(shared.contains("  device-gate:\n"));

        assertEquals(1, occurrences(ci, CALL));
        assertEquals(1, occurrences(release, CALL));
        assertFalse(ci.contains("release: true"));
        assertTrue(
                release.contains(
                        "with:\n"
                                + "      release: true\n"
                                + "      dry_run: ${{ inputs.dry_run }}"));
        assertTrue(release.contains("needs: [gates]"));

        assertFalse(ci.contains("host-gate:"));
        assertFalse(ci.contains("device-gate:"));
        assertFalse(ci.contains("android-actions/setup-android@"));
        int stage = release.indexOf("  stage:\n");
        assertTrue(stage > 0);
        String releaseBeforeStage = release.substring(0, stage);
        assertFalse(releaseBeforeStage.contains("host-gate:"));
        assertFalse(releaseBeforeStage.contains("device-gate:"));
        assertFalse(releaseBeforeStage.contains("android-actions/setup-android@"));

        assertEquals(1, occurrences(shared, "bash gradle/verify-configuration-cache-reuse.sh"));
        assertEquals(1, occurrences(shared, "bash gradle/run-samples-conformance-matrix.sh"));
        assertEquals(1, occurrences(shared, "bash gradle/run-engine-loaded-conformance-gate.sh"));
        assertEquals(1, occurrences(shared, "- name: Create and launch the API 36 emulator"));
    }

    private static int occurrences(String value, String needle) {
        return value.split(Pattern.quote(needle), -1).length - 1;
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath));
    }
}
