package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;

class FoundryEnumValidationTest {
    @Test
    void capturesEveryDirectEnumPositionOnceWithDeterministicTransportMetadata()
            throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "demo.UserState",
                """
                package demo;
                import games.cafecito.foundry.annotations.FoundryEnumValue;
                public enum UserState {
                    @FoundryEnumValue(Long.MAX_VALUE) ZETA,
                    @FoundryEnumValue(0) MIDDLE,
                    @FoundryEnumValue(Long.MIN_VALUE) ALPHA
                }
                """);
        sources.put(
                "demo.GeneratedState",
                """
                package demo;
                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                @GeneratedByFoundry
                public enum GeneratedState {
                    FIRST(1),
                    SECOND(2);
                    private final long value;
                    GeneratedState(long value) { this.value = value; }
                    public long value() { return value; }
                    public static GeneratedState fromValue(long value) {
                        for (GeneratedState candidate : values()) {
                            if (candidate.value == value) { return candidate; }
                        }
                        throw new IllegalArgumentException();
                    }
                }
                """);
        sources.put(
                "demo.EngineNode",
                """
                package demo;
                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                import games.cafecito.foundry.annotations.FoundryVirtual;
                @GeneratedByFoundry
                public class EngineNode {
                    @FoundryVirtual("_state")
                    public UserState state(UserState value) { return value; }
                }
                """);
        sources.put(
                "demo.SignalParent",
                """
                package demo;
                public interface SignalParent<T> {
                    void emitted(T value);
                }
                """);
        sources.put(
                "demo.EnumExtension",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                public final class EnumExtension extends EngineNode {
                    @FoundryMethod
                    public UserState echo(UserState value) { return value; }
                    @FoundryMethod
                    public GeneratedState generated(GeneratedState value) { return value; }
                    @FoundryOverride
                    public UserState state(UserState value) { return value; }
                    @FoundryProperty(getter = "current", setter = "current")
                    private UserState current;
                    public UserState current() { return current; }
                    public void current(UserState value) { current = value; }
                    @FoundrySignal
                    public interface Changed extends SignalParent<UserState> {}
                }
                """);
        CapturingProcessor processor = new CapturingProcessor();

        ProcessorCompilation.Result result =
                ProcessorCompilation.compile(
                        sources,
                        null,
                        List.of(processor),
                        Files.createTempDirectory("enum-model-"),
                        name -> false);

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        ExtensionModel model = processor.models.get("demo.EnumExtension");
        assertEquals(
                List.of("demo.GeneratedState", "demo.UserState"),
                model.enums().stream().map(ExtensionModel.EnumModel::qualifiedName).toList());
        assertEquals(
                ExtensionModel.EnumOrigin.GENERATED,
                model.enumModel("demo.GeneratedState").orElseThrow().origin());
        assertEquals(List.of(), model.enumModel("demo.GeneratedState").orElseThrow().constants());
        assertEquals(
                List.of(
                        new ExtensionModel.EnumConstantModel("ALPHA", Long.MIN_VALUE),
                        new ExtensionModel.EnumConstantModel("MIDDLE", 0),
                        new ExtensionModel.EnumConstantModel("ZETA", Long.MAX_VALUE)),
                model.enumModel("demo.UserState").orElseThrow().constants());
        model.methods()
                .forEach(
                        method -> {
                            assertEquals("long", model.transportType(method.returnType()));
                            method.parameters()
                                    .forEach(
                                            parameter ->
                                                    assertEquals(
                                                            "long",
                                                            model.transportType(parameter.type())));
                        });
        model.overrides()
                .forEach(
                        method -> {
                            assertEquals("long", model.transportType(method.returnType()));
                            method.parameters()
                                    .forEach(
                                            parameter ->
                                                    assertEquals(
                                                            "long",
                                                            model.transportType(parameter.type())));
                        });
        assertEquals("long", model.transportType(model.properties().get(0).type()));
        assertEquals(
                "long", model.transportType(model.signals().get(0).parameters().get(0).type()));
    }

    @Test
    void requiresEveryUserConstantMappingWithoutRepeatingDiagnosticsAcrossUses()
            throws IOException {
        Map<String, String> sources = baseSources();
        sources.put(
                "demo.PartialState",
                """
                package demo;
                import games.cafecito.foundry.annotations.FoundryEnumValue;
                public enum PartialState {
                    @FoundryEnumValue(1) READY,
                    MISSING
                }
                """);
        sources.put(
                "demo.PartialExtension",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                public final class PartialExtension extends EngineNode {
                    @FoundryMethod
                    public PartialState echo(PartialState value) { return value; }
                    @FoundryProperty(getter = "state")
                    private PartialState state;
                    public PartialState state() { return state; }
                    @FoundrySignal
                    public interface Changed { void emitted(PartialState value); }
                }
                """);

        ProcessorCompilation.Result result = ProcessorCompilation.compile(sources);

        assertDiagnostic(
                result, "enum constant MISSING must declare @FoundryEnumValue", "PartialState", 5);
        assertNoRegistrationArtifacts(result);
    }

    @Test
    void rejectsDuplicateValuesOnTheLaterConstantAcrossTheSignedLongRange() throws IOException {
        Map<String, String> sources = baseSources();
        sources.put(
                "demo.DuplicateState",
                """
                package demo;
                import games.cafecito.foundry.annotations.FoundryEnumValue;
                public enum DuplicateState {
                    @FoundryEnumValue(Long.MIN_VALUE) FIRST,
                    @FoundryEnumValue(Long.MAX_VALUE) LAST,
                    @FoundryEnumValue(Long.MIN_VALUE) DUPLICATE
                }
                """);
        sources.put(
                "demo.DuplicateExtension",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                public final class DuplicateExtension extends EngineNode {
                    @FoundryMethod
                    public DuplicateState echo(DuplicateState value) { return value; }
                }
                """);

        ProcessorCompilation.Result result = ProcessorCompilation.compile(sources);

        assertDiagnostic(
                result,
                "duplicate @FoundryEnumValue -9223372036854775808; already used by FIRST",
                "DuplicateState",
                6);
        assertNoRegistrationArtifacts(result);
    }

    @Test
    void rejectsEmptyAndInaccessibleUserEnumsAtTheirDeclarations() throws IOException {
        Map<String, String> sources = baseSources();
        sources.put(
                "demo.EmptyState",
                """
                package demo;
                public enum EmptyState {
                    ;
                }
                """);
        sources.put(
                "demo.InaccessibleExtension",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                public final class InaccessibleExtension extends EngineNode {
                    private enum PrivateState {
                        @FoundryEnumValue(1) ONLY
                    }
                    private static final class Hidden {
                        public enum NestedState {
                            @FoundryEnumValue(2) ONLY
                        }
                    }
                    @FoundryMethod public EmptyState empty(EmptyState value) { return value; }
                    @FoundryMethod public PrivateState direct(PrivateState value) { return value; }
                    @FoundryMethod public Hidden.NestedState nested(Hidden.NestedState value) {
                        return value;
                    }
                }
                """);

        ProcessorCompilation.Result result = ProcessorCompilation.compile(sources);

        assertDiagnostic(
                result,
                "user enum demo.EmptyState must declare at least one enum constant",
                "EmptyState",
                2);
        assertDiagnostic(
                result,
                "user enum demo.InaccessibleExtension.PrivateState is not accessible"
                        + " to its generated sibling trampoline because"
                        + " demo.InaccessibleExtension.PrivateState is private",
                "InaccessibleExtension",
                5);
        assertDiagnostic(
                result,
                "user enum demo.InaccessibleExtension.Hidden.NestedState is not accessible"
                        + " to its generated sibling trampoline because"
                        + " demo.InaccessibleExtension.Hidden is private",
                "InaccessibleExtension",
                9);
        assertNoRegistrationArtifacts(result);
    }

    @Test
    void rejectsFoundryEnumValueOnAnOrdinaryField() throws IOException {
        ProcessorCompilation.Result result =
                ProcessorCompilation.compile(
                        Map.of(
                                "demo.Misplaced",
                                """
                                package demo;
                                import games.cafecito.foundry.annotations.FoundryEnumValue;
                                public final class Misplaced {
                                    @FoundryEnumValue(1)
                                    public static final long VALUE = 1;
                                }
                                """));

        assertDiagnostic(
                result, "@FoundryEnumValue may only annotate an enum constant", "Misplaced", 5);
    }

    @Test
    void readsClassRetainedEnumMappingsFromADependencyJar() throws IOException {
        Path dependencyOutput = Files.createTempDirectory("foundry-enum-dependency-");
        ProcessorCompilation.Result dependency =
                ProcessorCompilation.compile(
                        Map.of(
                                "dependency.ExternalState",
                                """
                                package dependency;
                                import games.cafecito.foundry.annotations.FoundryEnumValue;
                                public enum ExternalState {
                                    @FoundryEnumValue(Long.MIN_VALUE) FIRST,
                                    @FoundryEnumValue(Long.MAX_VALUE) LAST
                                }
                                """),
                        null,
                        List.of(),
                        dependencyOutput,
                        name -> false);
        assertTrue(dependency.successful(), () -> dependency.errorMessages().toString());
        Path dependencyJar = jar(dependency);
        Map<String, String> sources = baseSources();
        sources.put(
                "demo.DependencyExtension",
                """
                package demo;
                import dependency.ExternalState;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                public final class DependencyExtension extends EngineNode {
                    @FoundryMethod
                    public ExternalState echo(ExternalState value) { return value; }
                }
                """);

        ProcessorCompilation.Result result =
                ProcessorCompilation.compileWithClasspath(
                        sources, "demo-module", List.of(dependencyJar));

        assertTrue(result.successful(), () -> result.errorMessages().toString());
    }

    @Test
    void rejectsGeneratedEnumsWithoutTheExactAccessibleConversionApi() throws IOException {
        Map<String, String> sources = baseSources();
        sources.put(
                "demo.MissingGenerated",
                """
                package demo;
                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                @GeneratedByFoundry
                public enum MissingGenerated {
                    ONLY
                }
                """);
        sources.put(
                "demo.MalformedGenerated",
                """
                package demo;
                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                @GeneratedByFoundry
                public enum MalformedGenerated {
                    ONLY;
                    public int value() { return 1; }
                    public static long fromValue(long value) { return value; }
                }
                """);
        sources.put(
                "demo.InaccessibleGenerated",
                """
                package demo;
                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                @GeneratedByFoundry
                public enum InaccessibleGenerated {
                    ONLY;
                    private long value() { return 1; }
                    private static InaccessibleGenerated fromValue(long value) { return ONLY; }
                }
                """);
        sources.put(
                "demo.GeneratedConversionExtension",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                public final class GeneratedConversionExtension extends EngineNode {
                    @FoundryMethod public MissingGenerated missing(MissingGenerated value) {
                        return value;
                    }
                    @FoundryMethod public MalformedGenerated malformed(MalformedGenerated value) {
                        return value;
                    }
                    @FoundryMethod
                    public InaccessibleGenerated inaccessible(InaccessibleGenerated value) {
                        return value;
                    }
                }
                """);

        ProcessorCompilation.Result result = ProcessorCompilation.compile(sources);

        String diagnostic =
                "generated Foundry enum must declare public long value() and public static";
        assertDiagnostic(result, diagnostic, "MissingGenerated", 4);
        assertDiagnostic(result, diagnostic, "MalformedGenerated", 4);
        assertDiagnostic(result, diagnostic, "InaccessibleGenerated", 4);
        assertNoRegistrationArtifacts(result);
    }

    private static Map<String, String> baseSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "demo.EngineNode",
                """
                package demo;
                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                @GeneratedByFoundry
                public class EngineNode {}
                """);
        return sources;
    }

    private static Path jar(ProcessorCompilation.Result result) throws IOException {
        Path jar = result.outputDirectory().resolve("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry :
                    result.classOutput().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .toList()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }

    private static void assertDiagnostic(
            ProcessorCompilation.Result result, String text, String sourceName, long line) {
        assertFalse(result.successful(), "compilation unexpectedly succeeded");
        var matching =
                result.diagnostics().stream()
                        .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                        .filter(diagnostic -> diagnostic.getMessage(null).contains(text))
                        .filter(
                                diagnostic ->
                                        diagnostic
                                                .getSource()
                                                .getName()
                                                .endsWith("/" + sourceName + ".java"))
                        .toList();
        assertEquals(
                1,
                matching.size(),
                () -> "missing unique diagnostic '" + text + "': " + result.errorMessages());
        assertEquals(line, matching.get(0).getLineNumber(), matching.get(0).getMessage(null));
        assertTrue(
                matching.get(0).getColumnNumber() > 0, "diagnostic must include a source column");
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

    private static final class CapturingProcessor extends AbstractProcessor {
        private final Map<String, ExtensionModel> models = new LinkedHashMap<>();
        private ExtensionValidator validator;

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of(ExtensionValidator.CLASS);
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.RELEASE_17;
        }

        @Override
        public boolean process(
                Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
            if (validator == null) {
                validator = new ExtensionValidator(processingEnv);
            }
            TypeElement foundryClass =
                    processingEnv.getElementUtils().getTypeElement(ExtensionValidator.CLASS);
            if (foundryClass == null) {
                return true;
            }
            for (var element : roundEnvironment.getElementsAnnotatedWith(foundryClass)) {
                if (element instanceof TypeElement type) {
                    validator
                            .validate(type)
                            .ifPresent(model -> models.put(model.qualifiedName(), model));
                }
            }
            return true;
        }
    }
}
