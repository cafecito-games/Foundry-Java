package games.cafecito.foundry.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for issue #49: root {@code build.gradle.kts}'s {@code VerifyPublications}
 * task must scope every published-topology comparison to the version under test, ignoring
 * publications for any other version already staged in {@code build/repository}.
 *
 * <p>The task walks {@code build/repository} for files with a given extension, relativizes each
 * file's parent directory, and compares the resulting set against an exact expected topology.
 * Without scoping by version, a repository holding publications for two versions (for example, from
 * running {@code ./gradlew check} and then {@code ./gradlew -PfoundryVersion=0.1.0
 * verifyPublications}) reports the union of both versions as a topology mismatch, even though
 * neither publication is actually wrong.
 */
class VerifyPublicationsVersionScopeTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void everyPublishedTopologyComparisonIsWiredToTheVersionScopingFilter() throws IOException {
        String rootBuild = Files.readString(ROOT.resolve("build.gradle.kts"));
        assertTrue(
                rootBuild.contains("val versionDirectorySuffix = \"/$version\""),
                "VerifyPublications must derive an exact per-version directory suffix.");
        long scopingFilterUses =
                Stream.of(rootBuild.split("\n"))
                        .filter(line -> line.contains("versionDirectorySuffix"))
                        .count();
        assertTrue(
                scopingFilterUses >= 3,
                "Every published-topology comparison (POM, module, archive) must reference the"
                        + " version-scoping filter, found "
                        + scopingFilterUses);
    }

    @Test
    void filteringByVersionSuffixIgnoresOtherVersionsStagedInTheSameRepository()
            throws IOException {
        Path repository = Files.createTempDirectory("verify-publications-version-scope-test");
        stagePom(repository, "games/cafecito/foundry/foundry-java-runtime/0.1.0");
        stagePom(repository, "games/cafecito/foundry/foundry-java-annotations/0.1.0");
        stagePom(repository, "games/cafecito/foundry/foundry-java-runtime/0.1.0-SNAPSHOT");
        stagePom(repository, "games/cafecito/foundry/foundry-java-annotations/0.1.0-SNAPSHOT");

        Set<String> expectedForVersionUnderTest =
                Set.of(
                        "games/cafecito/foundry/foundry-java-runtime/0.1.0",
                        "games/cafecito/foundry/foundry-java-annotations/0.1.0");

        assertNotEquals(
                expectedForVersionUnderTest,
                publishedPomDirectories(repository, null),
                "An unscoped comparison must be corrupted by the other staged version -- this is"
                        + " the exact defect issue #49 fixed.");
        assertEquals(
                expectedForVersionUnderTest,
                publishedPomDirectories(repository, "0.1.0"),
                "Scoping by version must ignore publications belonging to other versions.");
    }

    private static void stagePom(Path repository, String publicationDirectory) throws IOException {
        Path directory = repository.resolve(publicationDirectory);
        Files.createDirectories(directory);
        String artifactId = directory.getParent().getFileName().toString();
        String version = directory.getFileName().toString();
        Files.createFile(directory.resolve(artifactId + "-" + version + ".pom"));
    }

    private static Set<String> publishedPomDirectories(Path repository, String scopedVersion)
            throws IOException {
        try (Stream<Path> walk = Files.walk(repository)) {
            Stream<String> directories =
                    walk.filter(path -> path.toString().endsWith(".pom"))
                            .map(
                                    path ->
                                            repository
                                                    .relativize(path.getParent())
                                                    .toString()
                                                    .replace('\\', '/'));
            if (scopedVersion != null) {
                String suffix = "/" + scopedVersion;
                directories = directories.filter(value -> value.endsWith(suffix));
            }
            return directories.collect(Collectors.toSet());
        }
    }
}
