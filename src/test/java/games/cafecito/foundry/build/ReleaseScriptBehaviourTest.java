package games.cafecito.foundry.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executes the release scripts so every refusal the release contract claims is observed rather than
 * asserted from source text.
 *
 * <p>The legs that need real credentials cannot run here: no Maven Central upload happens, and no
 * production signing key is used. Signing is exercised with an ephemeral key generated for this
 * test, which is exactly the material the staged-repository verifier consumes in production.
 */
class ReleaseScriptBehaviourTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final String VERSION = "1.2.3";
    private static final String GROUP = "games.cafecito.foundry";
    private static Path signingHome;
    private static Path publicKey;
    private static String signingKeyIdentity;

    @BeforeAll
    static void generateAnEphemeralSigningKey() throws Exception {
        // GnuPG puts its agent socket inside GNUPGHOME, and a Unix domain socket path is limited to
        // about 100 characters, which the platform temporary directory alone can already exceed.
        signingHome = Files.createTempDirectory(Path.of("/tmp"), "fjr");
        Files.setPosixFilePermissions(
                signingHome, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        signingKeyIdentity = "Foundry Release Test <release-test@cafecito.games>";
        Result generated =
                run(
                        signingHome,
                        Map.of("GNUPGHOME", signingHome.toString()),
                        "gpg",
                        "--batch",
                        "--yes",
                        "--pinentry-mode",
                        "loopback",
                        "--passphrase",
                        "",
                        "--quick-generate-key",
                        signingKeyIdentity,
                        "default",
                        "default",
                        "never");
        assertEquals(0, generated.exitCode(), generated.output());
        publicKey = signingHome.resolve("public-key.asc");
        Result exported =
                run(
                        signingHome,
                        Map.of("GNUPGHOME", signingHome.toString()),
                        "gpg",
                        "--batch",
                        "--yes",
                        "--armor",
                        "--output",
                        publicKey.toString(),
                        "--export",
                        signingKeyIdentity);
        assertEquals(0, exported.exitCode(), exported.output());
        assertTrue(Files.size(publicKey) > 0);
    }

    @Test
    void preconditionsAcceptATagThatMatchesTheDeclaredVersionOnACleanTree(@TempDir Path directory)
            throws Exception {
        Path repository = newRepository(directory, "0.4.0");

        Result result = preconditions(repository, "v0.4.0");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("0.4.0"), result.output());
    }

    @Test
    void preconditionsRefuseATagThatDisagreesWithTheDeclaredVersion(@TempDir Path directory)
            throws Exception {
        Path repository = newRepository(directory, "0.4.0");
        tag(repository, "v0.5.0");

        Result result = preconditions(repository, "v0.5.0");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(
                result.output().contains("does not match the declared project version"),
                result.output());
    }

    @Test
    void preconditionsRefuseASnapshotDeclaredVersion(@TempDir Path directory) throws Exception {
        Path repository = newRepository(directory, "0.4.0-SNAPSHOT");
        tag(repository, "v0.4.0-SNAPSHOT");

        Result result = preconditions(repository, "v0.4.0-SNAPSHOT");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("is not a release version"), result.output());
    }

    @Test
    void preconditionsRefuseADirtyDependencyLock(@TempDir Path directory) throws Exception {
        Path repository = newRepository(directory, "0.4.0");
        Files.writeString(repository.resolve("gradle.lockfile"), "empty=changed\n");

        Result result = preconditions(repository, "v0.4.0");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("dependency lock"), result.output());
        assertTrue(result.output().contains("gradle.lockfile"), result.output());
    }

    @Test
    void preconditionsRefuseAnUncleanWorkingTreeIncludingUntrackedFiles(@TempDir Path directory)
            throws Exception {
        Path repository = newRepository(directory, "0.4.0");
        Files.writeString(repository.resolve("scratch.txt"), "left behind\n");

        Result result = preconditions(repository, "v0.4.0");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("working tree is not clean"), result.output());
    }

    @Test
    void preconditionsRefuseATagThatDoesNotPointAtHead(@TempDir Path directory) throws Exception {
        Path repository = newRepository(directory, "0.4.0");
        Files.writeString(repository.resolve("NOTICE.txt"), "second commit\n");
        assertEquals(0, git(repository, "add", "-A").exitCode());
        assertEquals(0, git(repository, "commit", "-m", "second").exitCode());

        Result result = preconditions(repository, "v0.4.0");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("does not point at HEAD"), result.output());
    }

    @Test
    void theStagedRepositoryVerifierAcceptsACompleteSignedAndChecksummedRelease(
            @TempDir Path directory) throws Exception {
        Path staging = newStagedRelease(directory);

        Result result = verifyStaged(staging);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.isRegularFile(staging.resolve("verification-summary.json")));
        assertTrue(
                Files.readString(staging.resolve("verification-summary.json")).contains("\"ok\""),
                "the summary records the verified outcome");
    }

    @Test
    void theStagedRepositoryVerifierRejectsAnUnsignedArtifact(@TempDir Path directory)
            throws Exception {
        Path staging = newStagedRelease(directory);
        Path jar = artifact(staging, "foundry-java-runtime", "jar", "");
        Files.delete(jar.resolveSibling(jar.getFileName() + ".asc"));

        Result result = verifyStaged(staging);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("is not signed"), result.output());
    }

    @Test
    void theStagedRepositoryVerifierRejectsATamperedArtifact(@TempDir Path directory)
            throws Exception {
        Path staging = newStagedRelease(directory);
        Path jar = artifact(staging, "foundry-java-runtime", "jar", "");
        writeZip(jar, "tampered/Extra.class", "tampered");
        writeChecksums(jar);

        Result result = verifyStaged(staging);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("signature is invalid"), result.output());
    }

    @Test
    void theStagedRepositoryVerifierRejectsAChecksumThatDoesNotMatch(@TempDir Path directory)
            throws Exception {
        Path staging = newStagedRelease(directory);
        Path jar = artifact(staging, "foundry-java-runtime", "jar", "");
        Files.writeString(jar.resolveSibling(jar.getFileName() + ".sha256"), "0".repeat(64) + "\n");

        Result result = verifyStaged(staging);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("checksum"), result.output());
    }

    @Test
    void theStagedRepositoryVerifierRejectsAModuleWithoutSourcesOrJavadoc(@TempDir Path directory)
            throws Exception {
        Path staging = newStagedRelease(directory);
        Path sources = artifact(staging, "foundry-java-runtime", "jar", "sources");
        for (String suffix : List.of("", ".asc", ".md5", ".sha1", ".sha256", ".sha512")) {
            Files.deleteIfExists(sources.resolveSibling(sources.getFileName() + suffix));
        }

        Result result = verifyStaged(staging);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("-sources.jar"), result.output());
    }

    @Test
    void theStagedRepositoryVerifierRejectsAPomWithoutTheRequiredCentralMetadata(
            @TempDir Path directory) throws Exception {
        Path staging = newStagedRelease(directory);
        Path pom = artifact(staging, "foundry-java-runtime", "pom", "");
        Files.writeString(
                pom, Files.readString(pom).replaceAll("(?s)<developers>.*</developers>", ""));
        sign(pom);
        writeChecksums(pom);

        Result result = verifyStaged(staging);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("developers"), result.output());
    }

    @Test
    void uploadingRefusesAStagedRepositoryThatWasNeverVerified(@TempDir Path directory)
            throws Exception {
        Path staging = newStagedRelease(directory);

        Result result = uploadToStaging(staging, directory.resolve("target"));

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("has not been verified"), result.output());
    }

    @Test
    void uploadingIsIdempotentAndFailsLoudlyOnAnAlreadyPublishedCoordinate(@TempDir Path directory)
            throws Exception {
        Path staging = newStagedRelease(directory);
        assertEquals(0, verifyStaged(staging).exitCode());
        Path target = directory.resolve("target");

        Result first = uploadToStaging(staging, target);
        assertEquals(0, first.exitCode(), first.output());
        assertTrue(Files.isRegularFile(staging.resolve("upload-summary.json")));
        assertTrue(
                Files.isRegularFile(
                        target.resolve(
                                "games/cafecito/foundry/foundry-java-runtime/"
                                        + VERSION
                                        + "/foundry-java-runtime-"
                                        + VERSION
                                        + ".jar")));

        Result second = uploadToStaging(staging, target);

        assertNotEquals(0, second.exitCode(), second.output());
        assertTrue(second.output().contains("is already published"), second.output());
        assertTrue(second.output().contains("refusing to republish"), second.output());
    }

    @Test
    void uploadingRefusesTheCentralTargetWithoutAPortalToken(@TempDir Path directory)
            throws Exception {
        Path staging = newStagedRelease(directory);
        assertEquals(0, verifyStaged(staging).exitCode());

        Result result =
                run(
                        ROOT,
                        Map.of("FOUNDRY_RELEASE_TOPOLOGY", topologyFile().toString()),
                        "bash",
                        ROOT.resolve("gradle/upload-staged-release.sh").toString(),
                        staging.toString(),
                        VERSION,
                        "central");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("FOUNDRY_CENTRAL_PORTAL_TOKEN"), result.output());
    }

    private Result preconditions(Path repository, String tag) throws Exception {
        return run(
                repository,
                Map.of(),
                "bash",
                ROOT.resolve("gradle/verify-release-preconditions.sh").toString(),
                tag,
                repository.toString());
    }

    private Result verifyStaged(Path staging) throws Exception {
        return run(
                ROOT,
                Map.of("FOUNDRY_RELEASE_TOPOLOGY", topologyFile().toString()),
                "bash",
                ROOT.resolve("gradle/verify-staged-release.sh").toString(),
                staging.toString(),
                VERSION,
                publicKey.toString());
    }

    private Result uploadToStaging(Path staging, Path target) throws Exception {
        return run(
                ROOT,
                Map.of(
                        "FOUNDRY_RELEASE_TOPOLOGY",
                        topologyFile().toString(),
                        "FOUNDRY_RELEASE_STAGING_TARGET",
                        target.toString()),
                "bash",
                ROOT.resolve("gradle/upload-staged-release.sh").toString(),
                staging.toString(),
                VERSION,
                "staging");
    }

    private Path topologyFile() throws IOException {
        Path topology = signingHome.resolve("release-topology.txt");
        if (!Files.isRegularFile(topology)) {
            Files.writeString(
                    topology,
                    GROUP
                            + ":foundry-java-runtime:jar:sourcesElements+javadocElements\n"
                            + GROUP
                            + ":foundry-java-android:aar:sourcesElements\n"
                            + "games.cafecito.foundry.java:"
                            + "games.cafecito.foundry.java.gradle.plugin:pom:none\n");
        }
        return topology;
    }

    private Path newRepository(Path directory, String declaredVersion) throws Exception {
        Path repository = Files.createDirectories(directory.resolve("repository"));
        Files.writeString(
                repository.resolve("gradle.properties"),
                "org.gradle.caching=true\nfoundryVersion=" + declaredVersion + "\n");
        Files.writeString(repository.resolve("gradle.lockfile"), "empty=compileClasspath\n");
        Files.writeString(
                repository.resolve("settings-gradle.lockfile"), "empty=incomingCatalog\n");
        assertEquals(0, git(repository, "init", "--initial-branch=main").exitCode());
        assertEquals(
                0, git(repository, "config", "user.email", "release@cafecito.games").exitCode());
        assertEquals(0, git(repository, "config", "user.name", "Foundry Release").exitCode());
        assertEquals(0, git(repository, "add", "-A").exitCode());
        assertEquals(0, git(repository, "commit", "-m", "initial").exitCode());
        tag(repository, "v" + declaredVersion.replace("-SNAPSHOT", "-SNAPSHOT"));
        return repository;
    }

    private void tag(Path repository, String tag) throws Exception {
        git(repository, "tag", "--force", tag);
    }

    private Result git(Path repository, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(arguments));
        return run(repository, Map.of(), command.toArray(String[]::new));
    }

    private Path newStagedRelease(Path directory) throws Exception {
        Path staging = Files.createDirectories(directory.resolve("staging"));
        Files.createDirectories(staging.resolve("repository"));
        Files.writeString(
                staging.resolve("release-provenance.json"),
                "{\n  \"binding_version\": \"" + VERSION + "\"\n}\n");
        stageModule(staging, GROUP, "foundry-java-runtime", "jar");
        stageModule(staging, GROUP, "foundry-java-android", "aar");
        stageMarker(staging);
        return staging;
    }

    private void stageModule(Path staging, String group, String artifactId, String packaging)
            throws Exception {
        Path coordinate = coordinateDirectory(staging, group, artifactId);
        Files.createDirectories(coordinate);
        String base = artifactId + "-" + VERSION;
        publishFile(coordinate.resolve(base + ".pom"), pom(group, artifactId, packaging));
        publishFile(coordinate.resolve(base + ".module"), module(group, artifactId, packaging));
        publishZip(coordinate.resolve(base + "." + packaging), artifactId + "/Main.class");
        publishZip(coordinate.resolve(base + "-sources.jar"), artifactId + "/Main.java");
        publishZip(coordinate.resolve(base + "-javadoc.jar"), artifactId + "/index.html");
    }

    private void stageMarker(Path staging) throws Exception {
        String group = "games.cafecito.foundry.java";
        String artifactId = "games.cafecito.foundry.java.gradle.plugin";
        Path coordinate = coordinateDirectory(staging, group, artifactId);
        Files.createDirectories(coordinate);
        publishFile(
                coordinate.resolve(artifactId + "-" + VERSION + ".pom"),
                pom(group, artifactId, "pom"));
    }

    private Path coordinateDirectory(Path staging, String group, String artifactId) {
        return staging.resolve("repository")
                .resolve(group.replace('.', '/'))
                .resolve(artifactId)
                .resolve(VERSION);
    }

    private Path artifact(Path staging, String artifactId, String extension, String classifier) {
        String suffix = classifier.isEmpty() ? "" : "-" + classifier;
        return coordinateDirectory(staging, GROUP, artifactId)
                .resolve(artifactId + "-" + VERSION + suffix + "." + extension);
    }

    private void publishFile(Path path, String content) throws Exception {
        Files.writeString(path, content);
        sign(path);
        writeChecksums(path);
    }

    private void publishZip(Path path, String entry) throws Exception {
        writeZip(path, entry, entry);
        sign(path);
        writeChecksums(path);
    }

    private void writeZip(Path path, String entry, String content) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            ZipEntry zipEntry = new ZipEntry(entry);
            zipEntry.setTime(0L);
            output.putNextEntry(zipEntry);
            output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private void sign(Path path) throws Exception {
        Path signature = path.resolveSibling(path.getFileName() + ".asc");
        Files.deleteIfExists(signature);
        Result result =
                run(
                        path.getParent(),
                        Map.of("GNUPGHOME", signingHome.toString()),
                        "gpg",
                        "--batch",
                        "--yes",
                        "--pinentry-mode",
                        "loopback",
                        "--passphrase",
                        "",
                        "--local-user",
                        signingKeyIdentity,
                        "--armor",
                        "--detach-sign",
                        "--output",
                        signature.toString(),
                        path.toString());
        assertEquals(0, result.exitCode(), result.output());
    }

    private void writeChecksums(Path path) throws Exception {
        byte[] content = Files.readAllBytes(path);
        for (Map.Entry<String, String> algorithm :
                Map.of("md5", "MD5", "sha1", "SHA-1", "sha256", "SHA-256", "sha512", "SHA-512")
                        .entrySet()) {
            Files.writeString(
                    path.resolveSibling(path.getFileName() + "." + algorithm.getKey()),
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance(algorithm.getValue())
                                            .digest(content)));
        }
    }

    private String pom(String group, String artifactId, String packaging) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>%s</packaging>
                  <name>%s</name>
                  <description>Java-first Android integration surface for Foundry.</description>
                  <url>https://github.com/cafecito-games/Foundry-Java</url>
                  <licenses>
                    <license>
                      <name>Apache License, Version 2.0</name>
                      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
                    </license>
                  </licenses>
                  <developers>
                    <developer>
                      <name>Cafecito Games</name>
                      <url>https://github.com/cafecito-games</url>
                    </developer>
                  </developers>
                  <scm>
                    <url>https://github.com/cafecito-games/Foundry-Java</url>
                    <connection>scm:git:https://github.com/cafecito-games/Foundry-Java.git</connection>
                  </scm>
                </project>
                """
                .formatted(group, artifactId, VERSION, packaging, artifactId);
    }

    private String module(String group, String artifactId, String packaging) {
        return """
                {
                  "formatVersion": "1.1",
                  "component": {
                    "group": "%s",
                    "module": "%s",
                    "version": "%s"
                  },
                  "variants": [
                    {
                      "name": "sourcesElements",
                      "files": [{ "name": "%s-%s-sources.jar" }]
                    },
                    {
                      "name": "javadocElements",
                      "files": [{ "name": "%s-%s-javadoc.jar" }]
                    },
                    {
                      "name": "runtimeElements",
                      "files": [{ "name": "%s-%s.%s" }]
                    }
                  ]
                }
                """
                .formatted(
                        group,
                        artifactId,
                        VERSION,
                        artifactId,
                        VERSION,
                        artifactId,
                        VERSION,
                        artifactId,
                        VERSION,
                        packaging);
    }

    private static Result run(
            Path workingDirectory, Map<String, String> environment, String... command)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(
                process.waitFor(10, TimeUnit.MINUTES),
                "command timed out: " + String.join(" ", command));
        return new Result(process.exitValue(), output);
    }

    private record Result(int exitCode, String output) {}
}
