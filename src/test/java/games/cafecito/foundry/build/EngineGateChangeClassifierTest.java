package games.cafecito.foundry.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EngineGateChangeClassifierTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void documentationBrandingTemplatesAndTestSourcesAreSafeToSkip() throws Exception {
        assertEquals(
                new Decision(false, "safe-only"),
                classify(
                        """
                        [
                          "docs/engine-pin.md",
                          "README.md",
                          "assets/logo/foundry.svg",
                          ".github/ISSUE_TEMPLATE/bug.yml",
                          ".github/PULL_REQUEST_TEMPLATE.md",
                          "src/test/java/example/RepositoryTest.java",
                          "foundry-java-runtime/src/test/java/example/RuntimeTest.java",
                          "foundry-java-android/src/testFixtures/cpp/native_fixture.cpp",
                          "gradle/testFixtures/example/build.gradle.kts"
                        ]
                        """));
    }

    @Test
    void everyProductionIntegrationAndBuildCategoryRunsTheGate() throws Exception {
        for (String path :
                new String[] {
                    "foundry-java-android/src/main/java/example/Binding.java",
                    "foundry-java-runtime/src/main/java/example/Runtime.java",
                    "acceptance/project/main.fs",
                    "samples/conformance-java/build.gradle.kts",
                    "api/current/extension_api.json",
                    "build.gradle.kts",
                    "gradle.lockfile",
                    "gradle/engine-pin.json",
                    "gradle/run-engine-loaded-conformance-gate.sh",
                    ".github/workflows/gates.yml"
                }) {
            assertEquals(new Decision(true, "relevant"), classify("[\"" + path + "\"]"), path);
        }
    }

    @Test
    void aMixedOrUnknownChangeRunsTheGate() throws Exception {
        assertEquals(
                new Decision(true, "relevant"),
                classify(
                        "[\"docs/engine-pin.md\","
                                + "\"foundry-java-android/src/main/AndroidManifest.xml\"]"));
        assertEquals(new Decision(true, "relevant"), classify("[\"future-module/new-file.txt\"]"));
    }

    @Test
    void emptyOrMalformedInputFailsClosed() throws Exception {
        assertEquals(new Decision(true, "fail-closed"), classify("[]"));
        assertEquals(new Decision(true, "fail-closed"), classify("{}"));
        assertEquals(new Decision(true, "fail-closed"), classify("[\"\"]"));
        assertEquals(new Decision(true, "fail-closed"), classify("not-json"));
    }

    private static Decision classify(String input) throws Exception {
        Process process =
                new ProcessBuilder("bash", "gradle/classify-engine-gate-paths.sh")
                        .directory(ROOT.toFile())
                        .redirectErrorStream(true)
                        .start();
        process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "classifier timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        List<String> lines = output.lines().toList();
        assertEquals(2, lines.size(), output);
        assertTrue(lines.stream().allMatch(line -> line.matches("[^=]+=[^=]+")), output);
        Map<String, String> fields =
                lines.stream()
                        .map(line -> line.split("=", 2))
                        .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
        assertEquals(Set.of("run", "reason"), fields.keySet(), output);
        return new Decision(Boolean.parseBoolean(fields.get("run")), fields.get("reason"));
    }

    private record Decision(boolean run, String reason) {}
}
