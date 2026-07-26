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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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

    @Test
    void requestedAbiValidatesTheExactBridgeAndConfigurationPayload() throws IOException {
        Path binding =
                bindingAar(
                        temporaryDirectory.resolve("binding.aar"),
                        "demo",
                        "example.DemoRegistry",
                        List.of("arm64-v8a"));
        Path project = projectWithPayloads("missing-abi", List.of(binding), List.of("x86_64"));

        BuildResult failure = runAndFail(project, "generateFoundryJavaRegistry");

        assertTrue(failure.getOutput().contains(binding.toAbsolutePath().toString()));
        assertTrue(failure.getOutput().contains("missing abi=x86_64"));
    }

    @Test
    void duplicateBridgeAndConfigurationPayloadsFailBeforeGeneration() throws IOException {
        Path first =
                bindingAar(
                        temporaryDirectory.resolve("first.aar"),
                        "first",
                        "example.FirstRegistry",
                        List.of("x86_64"));
        Path second =
                bindingAar(
                        temporaryDirectory.resolve("second.aar"),
                        "second",
                        "example.SecondRegistry",
                        List.of("x86_64"));
        Path project =
                projectWithPayloads(
                        "duplicate-payloads", List.of(second, first), List.of("x86_64"));

        BuildResult failure = runAndFail(project, "generateFoundryJavaRegistry");

        assertTrue(failure.getOutput().contains(first.toAbsolutePath().toString()));
        assertTrue(failure.getOutput().contains(second.toAbsolutePath().toString()));
        assertTrue(failure.getOutput().contains("bridge_payload=true"));
        assertTrue(failure.getOutput().contains("configuration_payload=true"));
        assertFalse(Files.exists(project.resolve(INDEX)));
    }

    @Test
    void androidApplicationGetsLazyVariantRegistryTasks() throws IOException {
        Path project = androidProject("variant-wiring");

        BuildResult result = run(project, "tasks", "--all");

        assertTrue(result.getOutput().contains("generateDebugFoundryJavaRegistry"));
        assertTrue(result.getOutput().contains("generateReleaseFoundryJavaRegistry"));
    }

    @Test
    void androidRuntimeGraphGeneratesVariantAssetsAndJavaForTheRequestedAbi() throws IOException {
        Path binding =
                bindingAar(
                        temporaryDirectory.resolve("android-binding.aar"),
                        "demo",
                        "example.DemoRegistry",
                        List.of("x86_64"));
        Path project = androidProject("android-runtime-graph", binding, List.of("x86_64"));

        run(project, "generateReleaseFoundryJavaRegistry");

        Path variantIndex =
                project.resolve(
                        "build/generated/assets/generateReleaseFoundryJavaRegistry/"
                                + "foundry_java/registry-index-v2.txt");
        Path variantBootstrap =
                project.resolve(
                        "build/generated/java/generateReleaseFoundryJavaRegistry/"
                                + "games/cafecito/foundry/generated/"
                                + "FoundryGeneratedBootstrap.java");
        assertTrue(Files.readString(variantIndex).contains("module=demo|example.DemoRegistry"));
        assertTrue(Files.readString(variantBootstrap).contains("example.DemoRegistry.PROVIDER"));
    }

    @Test
    void minifiedReleasePackagesOneIndexConfigAndSelectedAbiWithCustomApplicationId()
            throws IOException {
        Path binding =
                bindingAar(
                        temporaryDirectory.resolve("minified-binding.aar"),
                        "demo",
                        "example.DemoRegistry",
                        List.of("arm64-v8a", "x86_64"));
        Path project = androidProject("minified-release", binding, List.of("x86_64"));
        writeBootstrapStubs(project);

        run(project, "assembleRelease", "--configuration-cache");
        BuildResult second = run(project, "assembleRelease", "--configuration-cache");

        assertTrue(second.getOutput().contains("Reusing configuration cache."));
        Path apk =
                Files.list(project.resolve("build/outputs/apk/release"))
                        .filter(path -> path.getFileName().toString().endsWith(".apk"))
                        .findFirst()
                        .orElseThrow();
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(apk.toFile())) {
            assertEquals(
                    1,
                    archive.stream()
                            .filter(
                                    entry ->
                                            entry.getName()
                                                    .equals(
                                                            "assets/foundry_java/"
                                                                    + "registry-index-v2.txt"))
                            .count());
            assertEquals(
                    1,
                    archive.stream()
                            .filter(entry -> entry.getName().equals("FoundryJava.foundryextension"))
                            .count());
            assertTrue(archive.getEntry("lib/x86_64/libfoundry_java.so") != null);
            assertFalse(archive.stream().anyMatch(entry -> entry.getName().contains("arm64-v8a")));
            assertFalse(
                    archive.stream()
                            .anyMatch(entry -> entry.getName().endsWith("libfoundry_android.so")));
        }
        String mapping =
                Files.readString(project.resolve("build/outputs/mapping/release/mapping.txt"));
        assertTrue(mapping.contains("games.cafecito.foundry.generated.FoundryGeneratedBootstrap"));
        assertTrue(mapping.contains("example.DemoRegistry"));
        assertTrue(
                Files.readString(project.resolve("build/outputs/apk/release/output-metadata.json"))
                        .contains("games.cafecito.test.custom"));
    }

    @Test
    void debugBuildUsesTheNamespaceAsTheDefaultApplicationId() throws IOException {
        Path binding =
                bindingAar(
                        temporaryDirectory.resolve("debug-binding.aar"),
                        "demo",
                        "example.DemoRegistry",
                        List.of("x86_64"));
        Path project = androidProject("debug-default-id", binding, List.of("x86_64"));
        Files.writeString(
                project.resolve("build.gradle"),
                Files.readString(project.resolve("build.gradle"))
                        .replace("        applicationId 'games.cafecito.test.custom'\n", ""));
        writeBootstrapStubs(project);

        run(project, "assembleDebug");

        assertTrue(
                Files.readString(project.resolve("build/outputs/apk/debug/output-metadata.json"))
                        .contains("games.cafecito.test"));
    }

    @Test
    void androidLibraryApplicationIsRejectedWithAConcretePluginDiagnostic() throws IOException {
        Path project = androidProject("library-misuse");
        Files.writeString(
                project.resolve("build.gradle"),
                Files.readString(project.resolve("build.gradle"))
                        .replace("com.android.application", "com.android.library"));

        BuildResult failure = runAndFail(project, "tasks");

        assertTrue(failure.getOutput().contains("requires com.android.application"));
        assertTrue(failure.getOutput().contains("com.android.library"));
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

    private Path projectWithPayloads(String name, List<Path> payloads, List<String> abis)
            throws IOException {
        Path project = project(name, payloads);
        String requestedAbis =
                abis.stream()
                        .map(abi -> "'" + abi + "'")
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("");
        Files.writeString(
                project.resolve("build.gradle"),
                Files.readString(project.resolve("build.gradle"))
                        + "\nfoundryJava { requestedAbis.set(["
                        + requestedAbis
                        + "]) }\n");
        return project;
    }

    private Path androidProject(String name) throws IOException {
        Path project = temporaryDirectory.resolve(name);
        Files.createDirectories(project.resolve("src/main"));
        Files.writeString(
                project.resolve("settings.gradle"),
                """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
                rootProject.name = '%s'
                """
                        .formatted(name));
        Files.writeString(
                project.resolve("build.gradle"),
                """
                plugins {
                    id 'com.android.application'
                    id 'games.cafecito.foundry.java'
                }
                android {
                    namespace 'games.cafecito.test'
                    compileSdk 36
                    defaultConfig {
                        applicationId 'games.cafecito.test.custom'
                        minSdk 23
                        targetSdk 36
                        versionCode 1
                        versionName '1'
                    }
                    buildTypes {
                        release {
                            minifyEnabled true
                            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt')
                        }
                    }
                }
                """);
        Files.writeString(project.resolve("src/main/AndroidManifest.xml"), "<manifest />\n");
        Path search = Path.of("").toAbsolutePath();
        while (search != null) {
            Path localProperties = search.resolve("local.properties");
            if (Files.isRegularFile(localProperties)) {
                Files.copy(localProperties, project.resolve("local.properties"));
                break;
            }
            search = search.getParent();
        }
        return project;
    }

    private void writeBootstrapStubs(Path project) throws IOException {
        Path runtime = project.resolve("src/main/java/games/cafecito/foundry/runtime");
        Path example = project.resolve("src/main/java/example");
        Files.createDirectories(runtime);
        Files.createDirectories(example);
        Files.writeString(
                runtime.resolve("FoundryModuleProvider.java"),
                """
                package games.cafecito.foundry.runtime;

                public interface FoundryModuleProvider {}
                """);
        Files.writeString(
                runtime.resolve("FoundryRegistryBootstrap.java"),
                """
                package games.cafecito.foundry.runtime;

                public final class FoundryRegistryBootstrap {
                    public FoundryRegistryBootstrap(
                            java.util.List<? extends FoundryModuleProvider> providers) {}
                }
                """);
        Files.writeString(
                example.resolve("DemoRegistry.java"),
                """
                package example;

                public final class DemoRegistry {
                    public static final games.cafecito.foundry.runtime.FoundryModuleProvider
                            PROVIDER =
                                    new games.cafecito.foundry.runtime.FoundryModuleProvider() {};

                    private DemoRegistry() {}
                }
                """);
    }

    private Path androidProject(String name, Path binding, List<String> requestedAbis)
            throws IOException {
        Path project = androidProject(name);
        String abis =
                requestedAbis.stream()
                        .map(abi -> "'" + abi + "'")
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("");
        Files.writeString(
                project.resolve("build.gradle"),
                Files.readString(project.resolve("build.gradle"))
                        + "\ndependencies { implementation files('"
                        + binding.toAbsolutePath()
                        + "') }\n"
                        + "foundryJava { requestedAbis.set(["
                        + abis
                        + "]) }\n");
        return project;
    }

    private BuildResult run(Path project, String... arguments) {
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .forwardOutput()
                .build();
    }

    private BuildResult runAndFail(Path project, String... arguments) {
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .forwardOutput()
                .buildAndFail();
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

    private Path bindingAar(Path output, String module, String registry, List<String> abis)
            throws IOException {
        Files.createDirectories(output.getParent());
        java.io.ByteArrayOutputStream classesBytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream classes = new JarOutputStream(classesBytes)) {
            JarEntry descriptor =
                    new JarEntry("META-INF/foundry-java/modules/" + module + ".descriptor");
            descriptor.setTime(0);
            classes.putNextEntry(descriptor);
            classes.write(descriptor(module, registry).getBytes(StandardCharsets.UTF_8));
            classes.closeEntry();
            JarEntry configuration = new JarEntry("FoundryJava.foundryextension");
            configuration.setTime(0);
            classes.putNextEntry(configuration);
            classes.write(
                    """
                    [configuration]
                    entry_symbol = "foundry_java_library_init"
                    """
                            .getBytes(StandardCharsets.UTF_8));
            classes.closeEntry();
        }
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(output))) {
            ZipEntry manifest = new ZipEntry("AndroidManifest.xml");
            manifest.setTime(0);
            archive.putNextEntry(manifest);
            archive.write(
                    ("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                                    + "package=\"games.cafecito.binding\" />\n")
                            .getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
            ZipEntry classes = new ZipEntry("classes.jar");
            classes.setTime(0);
            archive.putNextEntry(classes);
            archive.write(classesBytes.toByteArray());
            archive.closeEntry();
            ZipEntry consumerRules = new ZipEntry("proguard.txt");
            consumerRules.setTime(0);
            archive.putNextEntry(consumerRules);
            archive.write(
                    """
                    -keep class games.cafecito.foundry.generated.FoundryGeneratedBootstrap { *; }
                    -keep class example.DemoRegistry { *; }
                    """
                            .getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
            for (String abi : abis) {
                ZipEntry bridge = new ZipEntry("jni/" + abi + "/libfoundry_java.so");
                bridge.setTime(0);
                archive.putNextEntry(bridge);
                archive.write(new byte[] {0});
                archive.closeEntry();
            }
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
