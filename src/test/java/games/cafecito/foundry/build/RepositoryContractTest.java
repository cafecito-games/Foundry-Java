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
        assertTrue(
                Pattern.compile(
                                "buildToolsVersion\\s*=\\s*"
                                        + "libs\\.versions\\.android\\.build\\.tools\\s*"
                                        + "\\.get\\(\\)",
                                Pattern.DOTALL)
                        .matcher(androidBuild)
                        .find());
        assertTrue(
                workflow.contains(
                        "packages: 'tools platform-tools platforms;android-"
                                + compileSdkMatcher.group(1)
                                + " build-tools;"
                                + buildToolsMatcher.group(1)
                                + "'"));
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
        assertTrue(
                rootBuild.contains(
                        "tasks.named(\"check\") {"
                                + " dependsOn(\"spotlessCheck\", \"verifyRepositoryContract\") }"));
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

        assertTrue(rootBuild.contains("allowedBootstrapAndroidClasses = emptySet<String>()"));
        assertFalse(rootBuild.contains("substringAfterLast('/').contains(\"Host\")"));
        assertTrue(rootBuild.contains("libfoundry_android.so"));
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath));
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
