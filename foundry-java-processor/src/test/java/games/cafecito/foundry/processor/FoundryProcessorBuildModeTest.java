package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import org.junit.jupiter.api.Test;

class FoundryProcessorBuildModeTest {
    @Test
    void declaresAnAggregatingGradleProcessor() throws IOException {
        try (var stream =
                FoundryProcessorBuildModeTest.class.getResourceAsStream(
                        "/META-INF/gradle/incremental.annotation.processors")) {
            assertTrue(stream != null, "missing Gradle incremental processor metadata");
            assertEquals(
                    "games.cafecito.foundry.processor.FoundryExtensionProcessor,aggregating\n",
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void requiresAStableModuleIdentity() throws IOException {
        ProcessorCompilation.Result missing =
                ProcessorCompilation.compile(
                        FoundryTrampolineGenerationTest.extensionSources(), null);
        ProcessorCompilation.Result invalid =
                ProcessorCompilation.compile(
                        FoundryTrampolineGenerationTest.extensionSources(), "Demo Module");

        assertFalse(missing.successful());
        assertTrue(
                missing.errorMessages().stream()
                        .anyMatch(message -> message.contains("-Afoundry.module")));
        assertFalse(invalid.successful());
        assertTrue(
                invalid.errorMessages().stream()
                        .anyMatch(message -> message.contains("-Afoundry.module")));
    }

    @Test
    void incrementalRegenerationChangesOnlyAffectedArtifacts() throws IOException {
        Map<String, String> firstSources = twoExtensions(true);
        Map<String, String> secondSources = twoExtensions(false);

        ProcessorCompilation.Result first =
                ProcessorCompilation.compile(firstSources, "incremental-module");
        ProcessorCompilation.Result second =
                ProcessorCompilation.compile(secondSources, "incremental-module");

        assertTrue(first.successful(), () -> first.errorMessages().toString());
        assertTrue(second.successful(), () -> second.errorMessages().toString());
        assertEquals(
                first.generatedSources().get("demo/Stable_FoundryTrampoline.java"),
                second.generatedSources().get("demo/Stable_FoundryTrampoline.java"));
        assertArrayEquals(
                first.classOutput().get("demo/Stable_FoundryTrampoline.class"),
                second.classOutput().get("demo/Stable_FoundryTrampoline.class"));
        assertNotEquals(
                first.generatedSources()
                        .get(
                                "games/cafecito/foundry/generated/incrementalmodule/"
                                        + "IncrementalModuleRegistry.java"),
                second.generatedSources()
                        .get(
                                "games/cafecito/foundry/generated/incrementalmodule/"
                                        + "IncrementalModuleRegistry.java"));
        String secondDescriptor =
                new String(
                        second.classOutput()
                                .get(
                                        "META-INF/foundry-java/modules/"
                                                + "incremental-module.descriptor"),
                        StandardCharsets.UTF_8);
        assertFalse(secondDescriptor.contains("|temporary|"));
    }

    @Test
    void independentModulesReceiveDistinctStableArtifacts() throws IOException {
        ProcessorCompilation.Result alpha =
                ProcessorCompilation.compile(
                        FoundryTrampolineGenerationTest.extensionSources(), "alpha-module");
        ProcessorCompilation.Result beta =
                ProcessorCompilation.compile(
                        FoundryTrampolineGenerationTest.extensionSources(), "beta-module");

        assertTrue(alpha.successful(), () -> alpha.errorMessages().toString());
        assertTrue(beta.successful(), () -> beta.errorMessages().toString());
        assertTrue(
                alpha.generatedSources()
                        .containsKey(
                                "games/cafecito/foundry/generated/alphamodule/"
                                        + "AlphaModuleRegistry.java"));
        assertTrue(
                beta.generatedSources()
                        .containsKey(
                                "games/cafecito/foundry/generated/betamodule/"
                                        + "BetaModuleRegistry.java"));
        assertEquals(
                1,
                alpha.classOutput().keySet().stream()
                        .filter(path -> path.startsWith("META-INF/foundry-java/modules/"))
                        .count());
        assertEquals(
                1,
                beta.classOutput().keySet().stream()
                        .filter(path -> path.startsWith("META-INF/foundry-java/modules/"))
                        .count());
    }

    @Test
    void includesExtensionsGeneratedAfterAnInitiallyEmptyRound() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "demo.EngineNode",
                """
                package demo;
                public class EngineNode {}
                """);
        sources.put(
                "demo.InitialExtension",
                """
                package demo;
                import games.cafecito.foundry.annotations.FoundryClass;
                @FoundryClass(base = EngineNode.class)
                public final class InitialExtension extends EngineNode {}
                """);

        ProcessorCompilation.Result result =
                ProcessorCompilation.compile(
                        sources,
                        "delayed-module",
                        List.of(new FoundryExtensionProcessor(), new DelayedExtensionProcessor()));

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        String registry =
                result.generatedSources()
                        .get(
                                "games/cafecito/foundry/generated/delayedmodule/"
                                        + "DelayedModuleRegistry.java");
        assertTrue(registry.contains("\"demo.InitialExtension\""), registry);
        assertTrue(registry.contains("\"demo.GeneratedExtension\""), registry);
        assertTrue(
                result.generatedSources()
                        .containsKey("demo/GeneratedExtension_FoundryTrampoline.java"));
        String descriptor =
                new String(
                        result.classOutput()
                                .get(
                                        "META-INF/foundry-java/modules/"
                                                + "delayed-module.descriptor"),
                        StandardCharsets.UTF_8);
        assertTrue(descriptor.contains("class=demo.InitialExtension|"));
        assertTrue(descriptor.contains("class=demo.GeneratedExtension|"));
    }

    private static Map<String, String> twoExtensions(boolean includeTemporaryMethod) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "demo.EngineNode",
                """
                package demo;
                public class EngineNode {}
                """);
        sources.put(
                "demo.Stable",
                """
                package demo;
                import games.cafecito.foundry.annotations.FoundryClass;
                @FoundryClass(base = EngineNode.class)
                public final class Stable extends EngineNode {}
                """);
        sources.put(
                "demo.Changing",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                public final class Changing extends EngineNode {
                    %s
                }
                """
                        .formatted(
                                includeTemporaryMethod
                                        ? "@FoundryMethod public void temporary() {}"
                                        : ""));
        return sources;
    }

    private static final class DelayedExtensionProcessor extends AbstractProcessor {
        private int stage;

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.RELEASE_17;
        }

        @Override
        public boolean process(
                Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
            if (roundEnvironment.processingOver()) {
                return false;
            }
            try {
                if (stage == 0) {
                    writeSource(
                            "demo.DelayedTrigger",
                            """
                            package demo;
                            public final class DelayedTrigger {}
                            """);
                    stage = 1;
                } else if (stage == 1) {
                    writeSource(
                            "demo.GeneratedExtension",
                            """
                            package demo;
                            import games.cafecito.foundry.annotations.FoundryClass;
                            @FoundryClass(base = EngineNode.class)
                            public final class GeneratedExtension extends EngineNode {}
                            """);
                    stage = 2;
                }
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
            return false;
        }

        private void writeSource(String name, String source) throws IOException {
            try (Writer writer = processingEnv.getFiler().createSourceFile(name).openWriter()) {
                writer.write(source);
            }
        }
    }
}
