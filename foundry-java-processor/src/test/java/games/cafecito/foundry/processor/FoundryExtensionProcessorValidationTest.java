package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;

class FoundryExtensionProcessorValidationTest {
    private static final String BASE =
            """
            package demo;
            import games.cafecito.foundry.annotations.GeneratedByFoundry;
            import games.cafecito.foundry.annotations.FoundryVirtual;
            @GeneratedByFoundry
            public class EngineNode {
                @FoundryVirtual("_process")
                public void _process(double delta) {}
            }
            """;

    @Test
    void acceptsACompleteExtensionDeclaration() throws IOException {
        ProcessorCompilation.Result result =
                compile(
                        "SpinningCube",
                        """
                        package demo;
                        import games.cafecito.foundry.annotations.*;
                        @FoundryClass(base = EngineNode.class)
                        @FoundryInitialization(InitializationLevel.SCENE)
                        public final class SpinningCube extends EngineNode {
                            @FoundryProperty(getter = "speed", setter = "speed")
                            private double speed;
                            public double speed() { return speed; }
                            public void speed(double value) { speed = value; }
                            @FoundryMethod public void reset() {}
                            @FoundryOverride public void _process(double delta) {}
                            @FoundrySignal public interface Reset {
                                void emitted(double previousSpeed);
                            }
                        }
                        """);

        assertTrue(result.successful(), () -> result.errorMessages().toString());
    }

    @Test
    void rejectsAMismatchedBaseAtTheAnnotationValue() throws IOException {
        ProcessorCompilation.Result result =
                compile(
                        "BadBase",
                        """
                        package demo;
                        import games.cafecito.foundry.annotations.FoundryClass;
                        @FoundryClass(base = java.lang.String.class)
                        public final class BadBase extends EngineNode {}
                        """);

        assertDiagnostic(
                result, "must directly extend declared base java.lang.String", "BadBase", 3);
    }

    @Test
    void rejectsABaseWithoutGeneratorProvenance() throws IOException {
        ProcessorCompilation.Result result =
                ProcessorCompilation.compile(
                        Map.of(
                                "demo.OrdinaryBase",
                                """
                                package demo;
                                public class OrdinaryBase {}
                                """,
                                "demo.OrdinaryExtension",
                                """
                                package demo;
                                import games.cafecito.foundry.annotations.FoundryClass;
                                @FoundryClass(base = OrdinaryBase.class)
                                public final class OrdinaryExtension extends OrdinaryBase {}
                                """));

        assertDiagnostic(
                result,
                "extension base must be a generated Foundry engine class",
                "OrdinaryExtension",
                3);
    }

    @Test
    void rejectsAnOverrideWithoutGeneratedVirtualProvenance() throws IOException {
        ProcessorCompilation.Result result =
                ProcessorCompilation.compile(
                        Map.of(
                                "demo.GeneratedBase",
                                """
                                package demo;
                                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                                @GeneratedByFoundry
                                public class GeneratedBase {
                                    public void ordinary() {}
                                }
                                """,
                                "demo.BadOverride",
                                """
                                package demo;
                                import games.cafecito.foundry.annotations.*;
                                @FoundryClass(base = GeneratedBase.class)
                                public final class BadOverride extends GeneratedBase {
                                    @FoundryOverride public void ordinary() {}
                                }
                                """));

        assertDiagnostic(
                result, "does not match a generated Foundry virtual method", "BadOverride", 5);
    }

    @Test
    void rejectsAnExplicitOverrideNameThatDiffersFromTheGeneratedVirtualIdentity()
            throws IOException {
        ProcessorCompilation.Result result =
                compile(
                        "MismatchedOverrideName",
                        """
                        package demo;
                        import games.cafecito.foundry.annotations.*;
                        @FoundryClass(base = EngineNode.class)
                        public final class MismatchedOverrideName extends EngineNode {
                            @FoundryOverride(name = "_physics_process")
                            public void _process(double delta) {}
                        }
                        """);

        assertDiagnostic(
                result,
                "Foundry override name _physics_process does not match generated Foundry virtual identity _process",
                "MismatchedOverrideName",
                6);
    }

    @Test
    void requiresLifecycleConstructionForCanonicalGeneratedBindings() throws IOException {
        ProcessorCompilation.Result result =
                ProcessorCompilation.compile(
                        Map.of(
                                "games.cafecito.foundry.generated.mock.EngineNode",
                                """
                                package games.cafecito.foundry.generated.mock;
                                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                                @GeneratedByFoundry
                                public class EngineNode {}
                                """,
                                "demo.MissingBindingConstructor",
                                """
                                package demo;
                                import games.cafecito.foundry.annotations.FoundryClass;
                                @FoundryClass(base = games.cafecito.foundry.generated.mock.EngineNode.class)
                                public final class MissingBindingConstructor
                                        extends games.cafecito.foundry.generated.mock.EngineNode {}
                                """));

        assertDiagnostic(
                result,
                "extension class must provide a public constructor accepting FoundryBindingContext and ObjectLease",
                "MissingBindingConstructor",
                4);
    }

    @Test
    void acceptsGeneratedBindingTypesInExportedMembersAndSignals() throws IOException {
        Map<String, String> sources = baseSources();
        sources.put(
                "demo.GeneratedValue",
                """
                package demo;
                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                @GeneratedByFoundry
                public final class GeneratedValue {}
                """);
        sources.put(
                "demo.GeneratedTypes",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                public final class GeneratedTypes extends EngineNode {
                    @FoundryMethod
                    public GeneratedValue transform(GeneratedValue value) { return value; }
                    @FoundrySignal
                    public interface Changed { void emitted(GeneratedValue value); }
                }
                """);

        ProcessorCompilation.Result result = ProcessorCompilation.compile(sources);

        assertTrue(result.successful(), () -> result.errorMessages().toString());
    }

    @Test
    void acceptsReadOnlyPropertiesAndAnExplicitMatchingRenamedVirtual() throws IOException {
        ProcessorCompilation.Result result =
                ProcessorCompilation.compile(
                        Map.of(
                                "demo.RenamedBase",
                                """
                                package demo;
                                import games.cafecito.foundry.annotations.*;
                                @GeneratedByFoundry
                                public class RenamedBase {
                                    @FoundryVirtual("_process")
                                    public void onProcess(double delta) {}
                                }
                                """,
                                "demo.ExplicitVirtual",
                                """
                                package demo;
                                import games.cafecito.foundry.annotations.*;
                                @FoundryClass(base = RenamedBase.class)
                                public final class ExplicitVirtual extends RenamedBase {
                                    @FoundryProperty(getter = "speed")
                                    private double speed;
                                    public double speed() { return speed; }
                                    @FoundryOverride(name = "_process")
                                    public void onProcess(double delta) {}
                                }
                                """));

        assertTrue(result.successful(), () -> result.errorMessages().toString());
    }

    @Test
    void rejectsGenericExportsAndMethodOverrideNameCollisions() throws IOException {
        ProcessorCompilation.Result result =
                compile(
                        "UnsupportedMembers",
                        """
                        package demo;
                        import games.cafecito.foundry.annotations.*;
                        @FoundryClass(base = EngineNode.class)
                        public final class UnsupportedMembers extends EngineNode {
                            @FoundryMethod public <T> void generic(T value) {}
                            @FoundryMethod(name = "_process") public void collide() {}
                            @FoundryOverride public void _process(double delta) {}
                        }
                        """);

        assertDiagnostic(
                result, "exported method cannot declare type parameters", "UnsupportedMembers", 5);
        assertDiagnostic(result, "duplicate exported name _process", "UnsupportedMembers", 7);
    }

    @Test
    void rejectsANonVoidSignalMethod() throws IOException {
        ProcessorCompilation.Result result =
                compile(
                        "NonVoidSignal",
                        """
                        package demo;
                        import games.cafecito.foundry.annotations.*;
                        @FoundryClass(base = EngineNode.class)
                        public final class NonVoidSignal extends EngineNode {
                            @FoundrySignal public interface Changed { int emitted(); }
                        }
                        """);

        assertDiagnostic(result, "signal method must return void", "NonVoidSignal", 5);
    }

    @Test
    void rejectsInvalidExtensionAndMemberShapes() throws IOException {
        ProcessorCompilation.Result result =
                compile(
                        "BadShape",
                        """
                        package demo;
                        import games.cafecito.foundry.annotations.*;
                        @FoundryClass(base = EngineNode.class)
                        class BadShape extends EngineNode {
                            @FoundryMethod public static void staticMethod() {}
                            @FoundryMethod public Thread unsupported(Thread value) { return value; }
                            @FoundryOverride public void _process(String delta) {}
                        }
                        """);

        assertDiagnostic(result, "extension class must be public", "BadShape", 4);
        assertDiagnostic(result, "extension class must be final", "BadShape", 4);
        assertDiagnostic(result, "exported method must be a public instance method", "BadShape", 5);
        assertDiagnostic(result, "unsupported Foundry return type java.lang.Thread", "BadShape", 6);
        assertDiagnostic(
                result, "unsupported Foundry parameter type java.lang.Thread", "BadShape", 6);
        assertDiagnostic(
                result, "does not match a generated Foundry virtual method", "BadShape", 7);
    }

    @Test
    void rejectsDuplicateNamesAndInvalidPropertyAccessors() throws IOException {
        ProcessorCompilation.Result result =
                compile(
                        "BadMembers",
                        """
                        package demo;
                        import games.cafecito.foundry.annotations.*;
                        @FoundryClass(base = EngineNode.class)
                        public final class BadMembers extends EngineNode {
                            @FoundryProperty(name = "value", getter = "read", setter = "write")
                            private int value;
                            public long read() { return value; }
                            public void write(String value) {}
                            @FoundryMethod(name = "value") public void collide() {}
                            @FoundryMethod(name = "same") public void first() {}
                            @FoundryMethod(name = "same") public void second() {}
                        }
                        """);

        assertDiagnostic(result, "getter read must return int", "BadMembers", 5);
        assertDiagnostic(result, "setter write must accept exactly int", "BadMembers", 5);
        assertDiagnostic(result, "duplicate exported name value", "BadMembers", 9);
        assertDiagnostic(result, "duplicate exported name same", "BadMembers", 11);
    }

    @Test
    void rejectsInvalidSignalsAndInitializationDependencies() throws IOException {
        Map<String, String> sources = baseSources();
        sources.put(
                "demo.BadSignals",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                @FoundryInitialization(after = String.class)
                public final class BadSignals extends EngineNode {
                    @FoundrySignal public class NotAnInterface {}
                    @FoundrySignal public interface TooMany {
                        void first();
                        int second();
                    }
                }
                """);
        ProcessorCompilation.Result result = ProcessorCompilation.compile(sources);

        assertDiagnostic(
                result, "initialization dependency must be a @FoundryClass", "BadSignals", 4);
        assertDiagnostic(result, "@FoundrySignal must annotate an interface", "BadSignals", 6);
        assertDiagnostic(
                result, "signal must declare exactly one abstract method", "BadSignals", 7);
    }

    @Test
    void acceptsAnInheritedSignalMethodAndCapturesItsSignature() throws IOException {
        ProcessorCompilation.Result result =
                compile(
                        "InheritedSignal",
                        """
                        package demo;
                        import games.cafecito.foundry.annotations.*;
                        @FoundryClass(base = EngineNode.class)
                        public final class InheritedSignal extends EngineNode {
                            public interface SignalParent<T> {
                                void emitted(T value);
                            }
                            @FoundrySignal
                            public interface Changed extends SignalParent<String> {}
                        }
                        """);

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        String descriptor =
                new String(
                        result.classOutput()
                                .get("META-INF/foundry-java/modules/demo-module.descriptor"),
                        StandardCharsets.UTF_8);
        assertTrue(
                descriptor.contains(
                        "signal=demo.InheritedSignal|Changed|Changed|void(java.lang.String)"),
                descriptor);
    }

    @Test
    void rejectsMultipleInheritedAbstractSignalMethods() throws IOException {
        ProcessorCompilation.Result result =
                compile(
                        "InheritedNonSam",
                        """
                        package demo;
                        import games.cafecito.foundry.annotations.*;
                        @FoundryClass(base = EngineNode.class)
                        public final class InheritedNonSam extends EngineNode {
                            public interface FirstParent { void first(); }
                            public interface SecondParent { void second(); }
                            @FoundrySignal
                            public interface InvalidSignal extends FirstParent, SecondParent {}
                        }
                        """);

        assertDiagnostic(
                result, "signal must declare exactly one abstract method", "InheritedNonSam", 8);
    }

    @Test
    void rejectsRegistrationCyclesAtEachDependency() throws IOException {
        Map<String, String> sources = baseSources();
        sources.put(
                "demo.First",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                @FoundryInitialization(after = Second.class)
                public final class First extends EngineNode {}
                """);
        sources.put(
                "demo.Second",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                @FoundryInitialization(after = First.class)
                public final class Second extends EngineNode {}
                """);
        ProcessorCompilation.Result result = ProcessorCompilation.compile(sources);

        assertDiagnostic(result, "initialization dependency cycle", "First", 4);
        assertDiagnostic(result, "initialization dependency cycle", "Second", 4);
    }

    @Test
    void rejectsAnInitializationDependencyOutsideTheCurrentCompilation() throws IOException {
        Path foreignOutput = Files.createTempDirectory("foundry-foreign-extension-test-");
        ProcessorCompilation.Result foreign =
                ProcessorCompilation.compile(
                        Map.of(
                                "foreign.ForeignExtension",
                                """
                                package foreign;
                                import games.cafecito.foundry.annotations.FoundryClass;
                                @FoundryClass(base = String.class)
                                public final class ForeignExtension {}
                                """),
                        null,
                        List.of(),
                        foreignOutput,
                        name -> false);
        assertTrue(foreign.successful(), () -> foreign.errorMessages().toString());

        Map<String, String> sources = baseSources();
        sources.put(
                "demo.DependsOnForeignExtension",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                @FoundryInitialization(after = foreign.ForeignExtension.class)
                public final class DependsOnForeignExtension extends EngineNode {}
                """);
        ProcessorCompilation.Result result =
                ProcessorCompilation.compileWithClasspath(
                        sources, "demo-module", List.of(foreignOutput.resolve("classes")));

        assertDiagnostic(
                result,
                "initialization dependency must be part of the current compilation",
                "DependsOnForeignExtension",
                4);
    }

    @Test
    void rejectsOrphanAnnotationsNestedClassesAndCheckedExceptions() throws IOException {
        Map<String, String> sources = baseSources();
        sources.put(
                "demo.Orphan",
                """
                package demo;
                import games.cafecito.foundry.annotations.FoundryMethod;
                public final class Orphan {
                    @FoundryMethod public void misplaced() {}
                }
                """);
        sources.put(
                "demo.Outer",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                public final class Outer {
                    @FoundryClass(base = EngineNode.class)
                    public static final class Nested extends EngineNode {}
                }
                """);
        sources.put(
                "demo.ThrowsChecked",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                public final class ThrowsChecked extends EngineNode {
                    @FoundryMethod public void load() throws java.io.IOException {}
                }
                """);
        ProcessorCompilation.Result result = ProcessorCompilation.compile(sources);

        assertDiagnostic(result, "@FoundryMethod must be enclosed by a @FoundryClass", "Orphan", 4);
        assertDiagnostic(result, "extension class must be top-level", "Outer", 5);
        assertDiagnostic(
                result, "exported method cannot declare checked exceptions", "ThrowsChecked", 5);
    }

    @Test
    void rejectsACheckedExceptionOnTheExtensionConstructor() throws IOException {
        ProcessorCompilation.Result result =
                compile(
                        "ThrowingConstructor",
                        """
                        package demo;
                        import games.cafecito.foundry.annotations.FoundryClass;
                        @FoundryClass(base = EngineNode.class)
                        public final class ThrowingConstructor extends EngineNode {
                            public ThrowingConstructor() throws java.io.IOException {}
                        }
                        """);

        assertDiagnostic(
                result,
                "extension constructor cannot declare checked exceptions",
                "ThrowingConstructor",
                5);
    }

    @Test
    void allowsUncheckedExceptionsOnConstructorsAndExportedMethods() throws IOException {
        ProcessorCompilation.Result result =
                compile(
                        "UncheckedExceptions",
                        """
                        package demo;
                        import games.cafecito.foundry.annotations.*;
                        @FoundryClass(base = EngineNode.class)
                        public final class UncheckedExceptions extends EngineNode {
                            public UncheckedExceptions() throws IllegalStateException {}
                            @FoundryMethod
                            public void invoke() throws IllegalArgumentException, AssertionError {}
                        }
                        """);

        assertTrue(result.successful(), () -> result.errorMessages().toString());
    }

    @Test
    void rejectsReusedAccessorsAndDuplicateModuleClassNames() throws IOException {
        Map<String, String> sources = baseSources();
        sources.put(
                "demo.FirstName",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class, name = "Duplicate")
                public final class FirstName extends EngineNode {
                    @FoundryProperty(getter = "readFirst")
                    private int first;
                    @FoundryProperty(getter = "readFirst")
                    private int second;
                    public int readFirst() { return first; }
                }
                """);
        sources.put(
                "demo.SecondName",
                """
                package demo;
                import games.cafecito.foundry.annotations.FoundryClass;
                @FoundryClass(base = EngineNode.class, name = "Duplicate")
                public final class SecondName extends EngineNode {}
                """);
        ProcessorCompilation.Result result = ProcessorCompilation.compile(sources);

        assertDiagnostic(result, "property accessor readFirst is already used", "FirstName", 7);
        assertDiagnostic(result, "duplicate exported class name Duplicate", "SecondName", 4);
        assertTrue(
                result.generatedSources().keySet().stream()
                        .noneMatch(path -> path.endsWith("Registry.java")),
                result.generatedSources().keySet().toString());
        assertFalse(
                result.classOutput()
                        .containsKey("META-INF/foundry-java/modules/demo-module.descriptor"));
        assertFalse(
                result.classOutput().containsKey("META-INF/proguard/foundry-java-demo-module.pro"));
    }

    @Test
    void rejectsInvalidNamesAndDuplicateInitializationDependencies() throws IOException {
        Map<String, String> sources = baseSources();
        sources.put(
                "demo.Dependency",
                """
                package demo;
                import games.cafecito.foundry.annotations.FoundryClass;
                @FoundryClass(base = EngineNode.class)
                public final class Dependency extends EngineNode {}
                """);
        sources.put(
                "demo.BadIdentity",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class, name = "bad|name")
                @FoundryInitialization(after = {Dependency.class, Dependency.class})
                public final class BadIdentity extends EngineNode {
                    @FoundryMethod(name = "bad-name") public void invalid() {}
                }
                """);
        ProcessorCompilation.Result result = ProcessorCompilation.compile(sources);

        assertDiagnostic(result, "invalid exported name bad|name", "BadIdentity", 3);
        assertDiagnostic(
                result, "duplicate initialization dependency demo.Dependency", "BadIdentity", 4);
        assertDiagnostic(result, "invalid exported name bad-name", "BadIdentity", 6);
    }

    @Test
    void rejectsAnUnnamedPackageExtensionAtItsClassDeclaration() throws IOException {
        ProcessorCompilation.Result result =
                ProcessorCompilation.compile(
                        Map.of(
                                "DefaultExtension",
                                """
                                import games.cafecito.foundry.annotations.FoundryClass;
                                @FoundryClass(base = DefaultBase.class)
                                public final class DefaultExtension extends DefaultBase {}
                                class DefaultBase {}
                                """));

        assertDiagnostic(
                result,
                "extension class must be declared in a named package",
                "DefaultExtension",
                3);
    }

    private static ProcessorCompilation.Result compile(String name, String source)
            throws IOException {
        Map<String, String> sources = baseSources();
        sources.put("demo." + name, source);
        return ProcessorCompilation.compile(sources);
    }

    private static Map<String, String> baseSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("demo.EngineNode", BASE);
        return sources;
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
        var diagnostic = matching.get(0);
        assertTrue(
                diagnostic.getSource().getName().endsWith("/" + sourceName + ".java"),
                diagnostic.getSource().getName());
        assertEquals(line, diagnostic.getLineNumber(), diagnostic.getMessage(null));
        assertTrue(diagnostic.getColumnNumber() > 0, "diagnostic must include a source column");
    }
}
