package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        ProcessorCompilation.Result colliding =
                ProcessorCompilation.compile(
                        FoundryTrampolineGenerationTest.extensionSources(), "a-1");
        ProcessorCompilation.Result digitWithinSegment =
                ProcessorCompilation.compile(
                        FoundryTrampolineGenerationTest.extensionSources(), "a1");
        ProcessorCompilation.Result keyword =
                ProcessorCompilation.compile(
                        FoundryTrampolineGenerationTest.extensionSources(), "class");

        assertFalse(missing.successful());
        assertTrue(
                missing.errorMessages().stream()
                        .anyMatch(message -> message.contains("-Afoundry.module")));
        assertFalse(invalid.successful());
        assertTrue(
                invalid.errorMessages().stream()
                        .anyMatch(message -> message.contains("-Afoundry.module")));
        assertFalse(colliding.successful());
        assertTrue(
                colliding.errorMessages().stream()
                        .anyMatch(message -> message.contains("-Afoundry.module")));
        assertTrue(
                digitWithinSegment.successful(),
                () -> digitWithinSegment.errorMessages().toString());
        assertFalse(keyword.successful());
        assertTrue(
                keyword.errorMessages().stream()
                        .anyMatch(
                                message ->
                                        message.contains("-Afoundry.module")
                                                && message.contains("Java keyword")));
        assertNoRegistrationArtifacts(missing);
        assertNoRegistrationArtifacts(invalid);
        assertNoRegistrationArtifacts(colliding);
        assertNoRegistrationArtifacts(keyword);
    }

    @Test
    void exactJdk17WarningsAsErrorsCompilationIsWarningFree() throws IOException {
        ProcessorCompilation.Result result =
                ProcessorCompilation.compileWithOptions(
                        FoundryTrampolineGenerationTest.extensionSources(),
                        "warning-free-module",
                        List.of("-Werror"));

        assertTrue(
                result.successful(),
                () ->
                        result.diagnostics().stream()
                                .map(
                                        diagnostic ->
                                                diagnostic.getKind()
                                                        + ": "
                                                        + diagnostic.getMessage(null))
                                .toList()
                                .toString());
        assertTrue(
                result.diagnostics().stream()
                        .noneMatch(
                                diagnostic ->
                                        diagnostic.getKind() == javax.tools.Diagnostic.Kind.WARNING
                                                || diagnostic.getKind()
                                                        == javax.tools.Diagnostic.Kind
                                                                .MANDATORY_WARNING),
                result.diagnostics().toString());
        assertTrue(
                result.classOutput()
                        .containsKey(
                                "games/cafecito/foundry/generated/warningfreemodule/"
                                        + "WarningFreeModuleRegistry.class"),
                result.classOutput().keySet().toString());
    }

    @Test
    void incrementalRegenerationChangesOnlyAffectedArtifacts() throws IOException {
        Map<String, String> firstSources = twoExtensions(true);
        Map<String, String> secondSources = twoExtensions(false);
        Path sharedOutput = Files.createTempDirectory("foundry-incremental-processor-test-");

        ProcessorCompilation.Result first =
                ProcessorCompilation.compile(firstSources, "incremental-module", sharedOutput);
        ProcessorCompilation.resetProcessorOutputs(sharedOutput);
        ProcessorCompilation.Result second =
                ProcessorCompilation.compile(secondSources, "incremental-module", sharedOutput);

        assertTrue(first.successful(), () -> first.errorMessages().toString());
        assertTrue(second.successful(), () -> second.errorMessages().toString());
        assertEquals(first.outputDirectory(), second.outputDirectory());
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
        String firstDescriptor =
                new String(
                        first.classOutput()
                                .get(
                                        "META-INF/foundry-java/modules/"
                                                + "incremental-module.descriptor"),
                        StandardCharsets.UTF_8);
        assertTrue(firstDescriptor.contains("|temporary|"));
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
                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                @GeneratedByFoundry
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

    @Test
    void emitsTheModuleAfterExternalRootsContinueUntilTheLastActiveRound() throws IOException {
        ProcessorCompilation.Result result =
                ProcessorCompilation.compile(
                        FoundryTrampolineGenerationTest.extensionSources(),
                        "continuous-module",
                        List.of(new FoundryExtensionProcessor(), new ContinuousRootProcessor()));

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        assertTrue(
                result.generatedSources()
                        .containsKey(
                                "games/cafecito/foundry/generated/continuousmodule/"
                                        + "ContinuousModuleRegistry.java"),
                result.generatedSources().keySet().toString());
        assertTrue(
                result.classOutput()
                        .containsKey("META-INF/foundry-java/modules/continuous-module.descriptor"),
                result.classOutput().keySet().toString());
        assertTrue(
                result.generatedSources().keySet().stream()
                        .noneMatch(path -> path.contains("FoundryProcessorRoundBarrier")),
                result.generatedSources().keySet().toString());
    }

    @Test
    void includesAnExtensionGeneratedInALaterActiveRound() throws IOException {
        ProcessorCompilation.Result result =
                ProcessorCompilation.compile(
                        FoundryTrampolineGenerationTest.extensionSources(),
                        "post-emission-module",
                        List.of(
                                new FoundryExtensionProcessor(),
                                new PostEmissionExtensionProcessor()));

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        String registry =
                result.generatedSources()
                        .get(
                                "games/cafecito/foundry/generated/postemissionmodule/"
                                        + "PostEmissionModuleRegistry.java");
        assertTrue(registry.contains("\"demo.LateExtension\""), registry);
        String descriptor =
                new String(
                        result.classOutput()
                                .get(
                                        "META-INF/foundry-java/modules/"
                                                + "post-emission-module.descriptor"),
                        StandardCharsets.UTF_8);
        assertTrue(descriptor.contains("class=demo.LateExtension|"), descriptor);
    }

    @Test
    void generationFailurePreventsAllModuleArtifacts() throws IOException {
        ProcessorCompilation.Result result =
                ProcessorCompilation.compile(
                        FoundryTrampolineGenerationTest.extensionSources(),
                        "failing-module",
                        List.of(new FoundryExtensionProcessor()),
                        Files.createTempDirectory("foundry-failing-filer-test-"),
                        name -> name.equals("demo.SpinningCube_FoundryTrampoline"));

        assertFalse(result.successful());
        assertTrue(
                result.errorMessages().stream()
                        .anyMatch(
                                message ->
                                        message.contains("cannot generate trampoline")
                                                && message.contains("injected output failure")),
                result.errorMessages().toString());
        assertTrue(
                result.generatedSources().keySet().stream()
                        .noneMatch(path -> path.endsWith("FailingModuleRegistry.java")),
                result.generatedSources().keySet().toString());
        assertFalse(
                result.classOutput()
                        .containsKey("META-INF/foundry-java/modules/failing-module.descriptor"));
    }

    @Test
    void registryReservationAndResourceFailuresRemainFailClosed() throws IOException {
        ProcessorCompilation.Result registryFailure =
                ProcessorCompilation.compile(
                        FoundryTrampolineGenerationTest.extensionSources(),
                        "failing-registry",
                        List.of(new FoundryExtensionProcessor()),
                        Files.createTempDirectory("foundry-registry-filer-test-"),
                        name -> name.endsWith("FailingRegistryRegistry"));
        ProcessorCompilation.Result descriptorFailure =
                ProcessorCompilation.compile(
                        FoundryTrampolineGenerationTest.extensionSources(),
                        "failing-descriptor",
                        List.of(new FoundryExtensionProcessor()),
                        Files.createTempDirectory("foundry-descriptor-filer-test-"),
                        name -> name.endsWith("failing-descriptor.descriptor"));

        assertNoModuleArtifacts(registryFailure, "failing-registry");
        assertNoModuleArtifacts(descriptorFailure, "failing-descriptor");
    }

    private static Map<String, String> twoExtensions(boolean includeTemporaryMethod) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "demo.EngineNode",
                """
                package demo;
                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                @GeneratedByFoundry
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

    private static void assertNoModuleArtifacts(
            ProcessorCompilation.Result result, String moduleName) {
        assertFalse(result.successful());
        assertTrue(
                result.generatedSources().keySet().stream()
                        .noneMatch(path -> path.endsWith("Registry.java")),
                result.generatedSources().keySet().toString());
        assertFalse(
                result.classOutput()
                        .containsKey(
                                "META-INF/foundry-java/modules/" + moduleName + ".descriptor"));
        assertFalse(
                result.classOutput()
                        .containsKey("META-INF/proguard/foundry-java-" + moduleName + ".pro"));
    }

    private static void assertNoRegistrationArtifacts(ProcessorCompilation.Result result) {
        assertTrue(
                result.generatedSources().keySet().stream()
                        .noneMatch(
                                path ->
                                        path.endsWith("_FoundryTrampoline.java")
                                                || path.endsWith("Registry.java")),
                result.generatedSources().keySet().toString());
        assertTrue(
                result.classOutput().keySet().stream()
                        .noneMatch(
                                path ->
                                        path.startsWith("META-INF/foundry-java/modules/")
                                                || path.startsWith(
                                                        "META-INF/proguard/foundry-java-")),
                result.classOutput().keySet().toString());
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

    private static final class ContinuousRootProcessor extends AbstractProcessor {
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
            if (roundEnvironment.processingOver() || stage >= 2) {
                return false;
            }
            try (Writer writer =
                    processingEnv
                            .getFiler()
                            .createSourceFile("demo.ExternalActivity" + stage)
                            .openWriter()) {
                writer.write("package demo; public final class ExternalActivity" + stage + " {}");
                stage++;
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
            return false;
        }
    }

    private static final class PostEmissionExtensionProcessor extends AbstractProcessor {
        private boolean triggerWritten;
        private boolean extensionWritten;

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
            if (roundEnvironment.processingOver() || extensionWritten) {
                return false;
            }
            try {
                if (triggerWritten) {
                    writeSource(
                            "demo.LateExtension",
                            """
                            package demo;
                            import games.cafecito.foundry.annotations.FoundryClass;
                            import games.cafecito.foundry.runtime.FoundryBindingContext;
                            import games.cafecito.foundry.runtime.ObjectLease;
                            @FoundryClass(base = EngineNode.class)
                            public final class LateExtension extends EngineNode {
                                public LateExtension(
                                        FoundryBindingContext context, ObjectLease lease) {
                                    super(context, lease);
                                }
                            }
                            """);
                    extensionWritten = true;
                } else {
                    writeSource(
                            "demo.PostEmissionTrigger",
                            "package demo; public final class PostEmissionTrigger {}");
                    triggerWritten = true;
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
