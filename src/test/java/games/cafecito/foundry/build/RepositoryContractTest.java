package games.cafecito.foundry.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RepositoryContractTest {
    private static final String LOCK_COMMAND = "./gradlew --write-locks resolveAndLockAll";
    private static final Path ROOT = Path.of("").toAbsolutePath();
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
                read("foundry-java-gradle-plugin/build.gradle.kts")
                        .contains("games.cafecito.foundry.java"));
        assertTrue(read("gradle/libs.versions.toml").contains("com.android.library"));
    }

    @Test
    void platformBoundariesProtectThePublicJavaAbi() throws IOException {
        assertFalse(readTree("foundry-java-api-model").contains("android."));
        assertFalse(readTree("foundry-java-annotations").contains("android."));
        assertFalse(readTree("foundry-java-runtime").contains("android."));
        assertTrue(read("foundry-java-kotlin/build.gradle.kts").contains("foundry-java-runtime"));
        assertTrue(read("foundry-java-android/build.gradle.kts").contains("foundry-java-runtime"));
        assertFalse(readTree("foundry-java-android").contains("libfoundry_android.so"));
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
    void androidArtifactPolicyUsesAnExactBootstrapClassAllowlist() throws IOException {
        String rootBuild = read("build.gradle.kts");

        assertTrue(rootBuild.contains("allowedBootstrapAndroidClasses = emptySet<String>()"));
        assertFalse(rootBuild.contains("substringAfterLast('/').contains(\"Host\")"));
        assertTrue(rootBuild.contains("libfoundry_android.so"));
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath));
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
}
