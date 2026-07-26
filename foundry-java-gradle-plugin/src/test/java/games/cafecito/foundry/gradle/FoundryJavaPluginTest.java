package games.cafecito.foundry.gradle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoundryJavaPluginTest {
    private static final String API_SHA =
            "85e91174c1a8a48629223d6459bb2ef595ad1da405b2ce88435c24fe221aec51";
    private static final String INDEX =
            "build/generated/foundryJava/assets/foundry_java/registry-index-v2.txt";
    private static final String BOOTSTRAP =
            "build/generated/foundryJava/java/"
                    + "games/cafecito/foundry/generated/FoundryGeneratedBootstrap.java";

    @TempDir Path temporaryDirectory;

    @Test
    void zeroModulesProducesNoOptInMarkerOrBootstrap() throws IOException {
        Path project = project("zero", List.of());

        BuildResult result = run(project, "generateFoundryJavaRegistry");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateFoundryJavaRegistry").getOutcome());
        assertFalse(Files.exists(project.resolve(INDEX)));
        assertFalse(Files.exists(project.resolve(BOOTSTRAP)));
    }

    @Test
    void oneModuleProducesOneIndexAndDirectProviderBootstrap() throws IOException {
        Path alpha =
                moduleJar(
                        temporaryDirectory.resolve("alpha.jar"), "alpha", "example.AlphaRegistry");
        Path project = project("one", List.of(alpha));

        run(project, "generateFoundryJavaRegistry");

        assertEquals(
                """
                format=2
                api_sha256=%s
                generator_version=1
                runtime_contract_version=1
                bridge_contract_version=1
                module=alpha|example.AlphaRegistry
                """
                        .formatted(API_SHA),
                Files.readString(project.resolve(INDEX)));
        String bootstrap = Files.readString(project.resolve(BOOTSTRAP));
        assertTrue(bootstrap.contains("example.AlphaRegistry.PROVIDER"));
        assertTrue(
                bootstrap.contains("new games.cafecito.foundry.runtime.FoundryRegistryBootstrap"));
        assertFalse(bootstrap.contains("Class.forName"));
        assertFalse(bootstrap.contains("java.lang.reflect"));
    }

    @Test
    void dependencyOrderCannotChangeIndexOrBootstrapBytes() throws IOException {
        Path alpha =
                moduleJar(
                        temporaryDirectory.resolve("alpha.jar"), "alpha", "example.AlphaRegistry");
        Path zeta =
                moduleJar(temporaryDirectory.resolve("zeta.jar"), "zeta", "example.ZetaRegistry");
        Path project = project("reordered", List.of(zeta, alpha));

        run(project, "generateFoundryJavaRegistry");
        byte[] firstIndex = Files.readAllBytes(project.resolve(INDEX));
        byte[] firstBootstrap = Files.readAllBytes(project.resolve(BOOTSTRAP));

        writeBuild(project, List.of(alpha, zeta));
        run(project, "generateFoundryJavaRegistry", "--rerun-tasks");

        assertArrayEquals(firstIndex, Files.readAllBytes(project.resolve(INDEX)));
        assertArrayEquals(firstBootstrap, Files.readAllBytes(project.resolve(BOOTSTRAP)));
        assertEquals(
                List.of("module=alpha|example.AlphaRegistry", "module=zeta|example.ZetaRegistry"),
                Files.readAllLines(project.resolve(INDEX)).stream()
                        .filter(line -> line.startsWith("module="))
                        .toList());
    }

    @Test
    void transitiveDependencyDescriptorsAreAggregated() throws IOException {
        Path repository = temporaryDirectory.resolve("repository");
        publishModule(repository, "leaf", "example.LeafRegistry", List.of());
        publishModule(
                repository, "root", "example.RootRegistry", List.of("games.cafecito.test:leaf:1"));
        Path project = temporaryDirectory.resolve("transitive");
        Files.createDirectories(project);
        Files.writeString(
                project.resolve("settings.gradle"),
                "pluginManagement { repositories { gradlePluginPortal() } }\n"
                        + "dependencyResolutionManagement { repositories { maven { url = uri('"
                        + repository.toUri()
                        + "') } } }\n"
                        + "rootProject.name = 'transitive'\n");
        Files.writeString(
                project.resolve("build.gradle"),
                """
                plugins {
                    id 'games.cafecito.foundry.java'
                }
                dependencies {
                    foundryJavaModules 'games.cafecito.test:root:1'
                }
                """);

        run(project, "generateFoundryJavaRegistry");

        assertEquals(
                List.of("module=leaf|example.LeafRegistry", "module=root|example.RootRegistry"),
                Files.readAllLines(project.resolve(INDEX)).stream()
                        .filter(line -> line.startsWith("module="))
                        .toList());
    }

    @Test
    void registryGenerationReusesTheConfigurationCache() throws IOException {
        Path alpha =
                moduleJar(
                        temporaryDirectory.resolve("alpha.jar"), "alpha", "example.AlphaRegistry");
        Path project = project("configuration-cache", List.of(alpha));

        run(project, "generateFoundryJavaRegistry", "--configuration-cache");
        BuildResult second = run(project, "generateFoundryJavaRegistry", "--configuration-cache");

        assertTrue(second.getOutput().contains("Reusing configuration cache."));
        assertEquals(
                TaskOutcome.UP_TO_DATE, second.task(":generateFoundryJavaRegistry").getOutcome());
    }

    private Path project(String name, List<Path> modules) throws IOException {
        Path project = temporaryDirectory.resolve(name);
        Files.createDirectories(project);
        Files.writeString(
                project.resolve("settings.gradle"), "rootProject.name = '" + name + "'\n");
        writeBuild(project, modules);
        return project;
    }

    private void writeBuild(Path project, List<Path> modules) throws IOException {
        String dependencies =
                modules.stream()
                        .map(
                                path ->
                                        "    foundryJavaModules files('"
                                                + path.toAbsolutePath()
                                                + "')")
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("");
        Files.writeString(
                project.resolve("build.gradle"),
                """
                plugins {
                    id 'games.cafecito.foundry.java'
                }
                dependencies {
                %s
                }
                """
                        .formatted(dependencies));
    }

    private BuildResult run(Path project, String... arguments) {
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .forwardOutput()
                .build();
    }

    private Path moduleJar(Path output, String module, String registry) throws IOException {
        Files.createDirectories(output.getParent());
        try (JarOutputStream archive = new JarOutputStream(Files.newOutputStream(output))) {
            JarEntry entry =
                    new JarEntry("META-INF/foundry-java/modules/" + module + ".descriptor");
            entry.setTime(0);
            archive.putNextEntry(entry);
            archive.write(descriptor(module, registry).getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        return output;
    }

    private void publishModule(
            Path repository, String module, String registry, List<String> dependencies)
            throws IOException {
        Path version = repository.resolve("games/cafecito/test/" + module + "/1");
        Files.createDirectories(version);
        moduleJar(version.resolve(module + "-1.jar"), module, registry);
        String dependencyXml =
                dependencies.stream()
                        .map(
                                coordinate -> {
                                    String[] parts = coordinate.split(":");
                                    return """
                                            <dependency>
                                              <groupId>%s</groupId>
                                              <artifactId>%s</artifactId>
                                              <version>%s</version>
                                            </dependency>
                                            """
                                            .formatted(parts[0], parts[1], parts[2]);
                                })
                        .reduce((left, right) -> left + right)
                        .orElse("");
        Files.writeString(
                version.resolve(module + "-1.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>games.cafecito.test</groupId>
                  <artifactId>%s</artifactId>
                  <version>1</version>
                  <dependencies>
                %s
                  </dependencies>
                </project>
                """
                        .formatted(module, dependencyXml));
    }

    private String descriptor(String module, String registry) {
        return """
                format=2
                module=%s
                registry=%s
                api_sha256=%s
                generator_version=1
                runtime_contract_version=1
                bridge_contract_version=1
                class=example.Extension|Extension|Node|SCENE|
                """
                .formatted(module, registry, API_SHA);
    }
}
