package games.cafecito.foundry.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EngineGateApiResponseExtractorTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void extractsEveryDocumentedStatusAndPreviousPathInApiOrder() throws Exception {
        Result result =
                extract(
                        "7",
                        """
                        [
                          [
                            {"filename":"src/added.java","status":"added"},
                            {"filename":"src/removed.java","status":"removed"},
                            {"filename":"src/modified.java","status":"modified"}
                          ],
                          [
                            {
                              "filename":"src/renamed.java",
                              "previous_filename":"src/prior-renamed.java",
                              "status":"renamed"
                            },
                            {"filename":"src/copied.java","status":"copied"},
                            {
                              "filename":"src/changed.java",
                              "previous_filename":"src/prior-changed.java",
                              "status":"changed"
                            },
                            {"filename":"src/unchanged.java","status":"unchanged"}
                          ]
                        ]
                        """);

        assertEquals(0, result.status(), result.stderr());
        assertEquals(
                "[\"src/added.java\",\"src/removed.java\",\"src/modified.java\","
                        + "\"src/renamed.java\",\"src/prior-renamed.java\",\"src/copied.java\","
                        + "\"src/changed.java\",\"src/prior-changed.java\","
                        + "\"src/unchanged.java\"]\n",
                result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void changedFileCountMismatchHasItsOwnSilentExitCode() throws Exception {
        Result result =
                extract(
                        "2",
                        """
                        [[{"filename":"private/count-mismatch.java","status":"modified"}]]
                        """);

        assertSilentFailure(result, 2);
    }

    @Test
    void duplicateCurrentFilenamesAreRejectedSilently() throws Exception {
        Map<String, String> duplicates =
                Map.of(
                        "duplicate file records",
                        """
                        [[
                          {"filename":"private/duplicate.java","status":"modified"},
                          {"filename":"private/duplicate.java","status":"modified"}
                        ]]
                        """,
                        "same current filename with different metadata",
                        """
                        [[
                          {"filename":"private/replaced.java","status":"added"},
                          {"filename":"private/replaced.java","status":"modified"}
                        ]]
                        """);

        for (Map.Entry<String, String> fixture : duplicates.entrySet()) {
            assertSilentFailure(extract("2", fixture.getValue()), 1, fixture.getKey());
        }
    }

    @Test
    void duplicateCurrentFilenameIsRejectedBeforeCountMismatchClassification() throws Exception {
        Result result =
                extract(
                        "3",
                        """
                        [[
                          {"filename":"private/duplicate-count.java","status":"modified"},
                          {"filename":"private/duplicate-count.java","status":"modified"}
                        ]]
                        """);

        assertSilentFailure(result, 1);
    }

    @Test
    void malformedApiResponsesAreRejectedWithoutLeakingPaths() throws Exception {
        Map<String, String> malformed =
                Map.ofEntries(
                        Map.entry("malformed JSON", "not-json"),
                        Map.entry("non-array outer value", "{}"),
                        Map.entry("non-array page", "[{}]"),
                        Map.entry("empty response", "[]"),
                        Map.entry("empty page", "[[]]"),
                        Map.entry("non-object item", "[[42]]"),
                        Map.entry(
                                "missing filename",
                                "[[{\"status\":\"modified\",\"marker\":\"private/missing\"}]]"),
                        Map.entry(
                                "non-string filename",
                                "[[{\"filename\":7,\"status\":\"modified\"}]]"),
                        Map.entry(
                                "empty filename",
                                "[[{\"filename\":\"\",\"status\":\"modified\"}]]"),
                        Map.entry(
                                "missing status",
                                "[[{\"filename\":\"private/missing-status.java\"}]]"),
                        Map.entry(
                                "non-string status",
                                "[[{\"filename\":\"private/status-type.java\",\"status\":7}]]"),
                        Map.entry(
                                "empty status",
                                "[[{\"filename\":\"private/status-empty.java\",\"status\":\"\"}]]"),
                        Map.entry(
                                "non-string previous filename",
                                "[[{\"filename\":\"private/current-type.java\","
                                        + "\"previous_filename\":7,\"status\":\"modified\"}]]"),
                        Map.entry(
                                "empty previous filename",
                                "[[{\"filename\":\"private/current-empty.java\","
                                        + "\"previous_filename\":\"\",\"status\":\"modified\"}]]"),
                        Map.entry(
                                "renamed without previous filename",
                                "[[{\"filename\":\"private/renamed-missing.java\","
                                        + "\"status\":\"renamed\"}]]"),
                        Map.entry(
                                "renamed with null previous filename",
                                "[[{\"filename\":\"private/renamed-null.java\","
                                        + "\"previous_filename\":null,\"status\":\"renamed\"}]]"));

        for (Map.Entry<String, String> fixture : malformed.entrySet()) {
            assertSilentFailure(extract("1", fixture.getValue()), 1, fixture.getKey());
        }
    }

    @Test
    void unknownStatusIsRejectedSilently() throws Exception {
        assertSilentFailure(
                extract(
                        "1",
                        "[[{\"filename\":\"private/status-unknown.java\","
                                + "\"status\":\"banana\"}]]"),
                1);
    }

    @Test
    void statusWithTrailingWhitespaceIsRejectedSilently() throws Exception {
        assertSilentFailure(
                extract(
                        "1",
                        "[[{\"filename\":\"private/status-whitespace.java\","
                                + "\"status\":\"renamed \"}]]"),
                1);
    }

    @Test
    void presentNullPreviousFilenameIsRejectedSilently() throws Exception {
        assertSilentFailure(
                extract(
                        "1",
                        "[[{\"filename\":\"private/current-null.java\","
                                + "\"previous_filename\":null,\"status\":\"modified\"}]]"),
                1);
    }

    @Test
    void expectedCountMustBeOnePositiveInteger() throws Exception {
        for (String expected : new String[] {"", "invalid", "0", "-1", "1.5"}) {
            assertSilentFailure(extract(expected, null), 1, "expected count " + expected);
        }
    }

    private static void assertSilentFailure(Result result, int expectedStatus) {
        assertSilentFailure(result, expectedStatus, "extractor result");
    }

    private static void assertSilentFailure(Result result, int expectedStatus, String message) {
        assertEquals(expectedStatus, result.status(), message);
        assertEquals("", result.stdout(), message);
        assertEquals("", result.stderr(), message);
    }

    private static Result extract(String expectedCount, String input) throws Exception {
        Process process =
                new ProcessBuilder("bash", "gradle/extract-engine-gate-paths.sh", expectedCount)
                        .directory(ROOT.toFile())
                        .start();
        if (input != null) {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(
                    process.waitFor(10, TimeUnit.SECONDS),
                    "extractor did not stop after forced destruction");
            fail("extractor timed out");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.exitValue(), stdout, stderr);
    }

    private record Result(int status, String stdout, String stderr) {}
}
