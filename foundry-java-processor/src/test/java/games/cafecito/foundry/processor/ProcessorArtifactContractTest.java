package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class ProcessorArtifactContractTest {
    @Test
    void annotationsJarIsAPlatformNeutralDependencyFreeApi() throws IOException {
        Path jar = onlyJar(Path.of("..", "foundry-java-annotations", "build", "libs"));
        Set<String> classes = new TreeSet<>();
        List<String> productionText = new ArrayList<>();
        try (JarFile archive = new JarFile(jar.toFile())) {
            archive.stream()
                    .filter(entry -> !entry.isDirectory())
                    .forEach(
                            entry -> {
                                if (entry.getName().endsWith(".class")) {
                                    classes.add(entry.getName());
                                }
                                try {
                                    productionText.add(
                                            new String(
                                                    archive.getInputStream(entry).readAllBytes(),
                                                    StandardCharsets.ISO_8859_1));
                                } catch (IOException exception) {
                                    throw new java.io.UncheckedIOException(exception);
                                }
                            });
        }
        assertEquals(
                Set.of(
                        "games/cafecito/foundry/annotations/FoundryClass.class",
                        "games/cafecito/foundry/annotations/FoundryInitialization.class",
                        "games/cafecito/foundry/annotations/FoundryMethod.class",
                        "games/cafecito/foundry/annotations/FoundryOverride.class",
                        "games/cafecito/foundry/annotations/FoundryProperty.class",
                        "games/cafecito/foundry/annotations/FoundrySignal.class",
                        "games/cafecito/foundry/annotations/GeneratedByFoundry.class",
                        "games/cafecito/foundry/annotations/InitializationLevel.class",
                        "games/cafecito/foundry/annotations/PublicFoundryAbi.class"),
                classes);
        assertForbidden(productionText, "android/", "android.", "native ");
    }

    @Test
    void processorJarDeclaresOnlyCompileTimeDiscovery() throws IOException {
        Path jar = onlyJar(Path.of("build", "libs"));
        Set<String> metadata = new TreeSet<>();
        List<String> productionText = new ArrayList<>();
        try (JarFile archive = new JarFile(jar.toFile())) {
            archive.stream()
                    .filter(entry -> !entry.isDirectory())
                    .forEach(
                            entry -> {
                                if (entry.getName().startsWith("META-INF/")) {
                                    metadata.add(entry.getName());
                                }
                                try {
                                    productionText.add(
                                            new String(
                                                    archive.getInputStream(entry).readAllBytes(),
                                                    StandardCharsets.ISO_8859_1));
                                } catch (IOException exception) {
                                    throw new java.io.UncheckedIOException(exception);
                                }
                            });
        }
        assertTrue(
                metadata.contains("META-INF/services/javax.annotation.processing.Processor"),
                metadata.toString());
        assertTrue(
                metadata.contains("META-INF/gradle/incremental.annotation.processors"),
                metadata.toString());
        assertFalse(metadata.contains("AndroidManifest.xml"), metadata.toString());
        assertForbidden(
                productionText,
                "java/lang/reflect",
                "Class.forName",
                "getDeclaredMethod",
                "getDeclaredMethods",
                "dalvik/system/DexFile",
                "AndroidManifest.xml",
                "games.cafecito.foundry.plugin.v1");
    }

    @Test
    void authoringGuideDocumentsTheReflectionFreeContract() throws IOException {
        String guide =
                Files.readString(
                        Path.of("..", "docs", "java-authoring.md"), StandardCharsets.UTF_8);
        String normalizedGuide = guide.replaceAll("\\s+", " ");
        for (String required :
                new String[] {
                    "@FoundryClass",
                    "@FoundryMethod",
                    "@FoundryProperty",
                    "@FoundrySignal",
                    "@FoundryOverride",
                    "@FoundryInitialization",
                    "-Afoundry.module",
                    "no runtime reflection",
                    "libfoundry_android.so"
                }) {
            assertTrue(normalizedGuide.contains(required), required);
        }
    }

    private static Path onlyJar(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            List<Path> jars =
                    files.filter(path -> path.getFileName().toString().endsWith(".jar")).toList();
            assertEquals(1, jars.size(), directory.toString());
            return jars.get(0);
        }
    }

    private static void assertForbidden(List<String> values, String... forbidden) {
        for (String text : forbidden) {
            assertTrue(
                    values.stream().noneMatch(value -> value.contains(text)),
                    "forbidden production artifact text: " + text);
        }
    }
}
