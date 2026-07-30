package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FoundryEnumTrampolineGenerationTest {
    @Test
    void generatesDeterministicHelpersOnlyForEnumsUsedByTheTrampoline() throws IOException {
        ProcessorCompilation.Result result = ProcessorCompilation.compile(enumSources());

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        String generated =
                result.generatedSources().get("demo/EnumCallbacks_FoundryTrampoline.java");
        assertEquals(
                FoundryTrampolineGenerationTest.golden("EnumCallbacks_FoundryTrampoline.golden"),
                generated);
        assertFalse(generated.contains("SignalOnlyState"), generated);
        assertOrdered(generated, "enumInbound0", "enumInbound1", "enumOutbound0", "enumOutbound1");
    }

    @Test
    void transportsUserAndGeneratedEnumsAcrossEveryExecutableBoundary() throws Exception {
        ProcessorCompilation.Result result = ProcessorCompilation.compile(enumSources());

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        URL classes = result.outputDirectory().resolve("classes").toUri().toURL();
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {classes}, getClass().getClassLoader())) {
            Class<?> trampoline = loader.loadClass("demo.EnumCallbacks_FoundryTrampoline");
            Object target = trampoline.getMethod("construct").invoke(null);

            assertEquals(Long.MAX_VALUE, invoke(trampoline, target, "echoUser", Long.MIN_VALUE));
            assertEquals(Long.MIN_VALUE, invoke(trampoline, target, "echoUser", Long.MAX_VALUE));
            assertEquals(
                    Long.MAX_VALUE, invoke(trampoline, target, "echoGenerated", Long.MIN_VALUE));
            assertEquals(
                    Long.MIN_VALUE, invoke(trampoline, target, "echoGenerated", Long.MAX_VALUE));
            assertEquals(Long.MIN_VALUE, invoke(trampoline, target, "state", Long.MIN_VALUE));
            assertEquals(Long.MAX_VALUE, invoke(trampoline, target, "state", Long.MAX_VALUE));

            trampoline
                    .getMethod("setProperty", Object.class, String.class, Object.class)
                    .invoke(null, target, "current", Long.MIN_VALUE);
            assertEquals(
                    Long.MIN_VALUE,
                    trampoline
                            .getMethod("getProperty", Object.class, String.class)
                            .invoke(null, target, "current"));
            assertEquals(6, intValue(target, "callCount"));
            assertEquals(1, intValue(target, "mutationCount"));
        }
    }

    @Test
    void rejectsInvalidInboundValuesBeforeInvocationOrPropertyMutation() throws Exception {
        ProcessorCompilation.Result result = ProcessorCompilation.compile(enumSources());

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        URL classes = result.outputDirectory().resolve("classes").toUri().toURL();
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {classes}, getClass().getClassLoader())) {
            Class<?> trampoline = loader.loadClass("demo.EnumCallbacks_FoundryTrampoline");
            Object target = trampoline.getMethod("construct").invoke(null);
            Method invoke =
                    trampoline.getMethod("invoke", Object.class, String.class, Object[].class);
            Method setProperty =
                    trampoline.getMethod("setProperty", Object.class, String.class, Object.class);

            assertInvocationFailure(
                    "Expected boxed Long for enum demo.UserState",
                    () -> invoke.invoke(null, target, "echoUser", (Object) new Object[] {1}));
            assertInvocationFailure(
                    "Expected boxed Long for enum demo.UserState",
                    () -> invoke.invoke(null, target, "echoUser", (Object) new Object[] {null}));
            assertInvocationFailure(
                    "Unknown enum value 0 for demo.UserState",
                    () -> invoke.invoke(null, target, "echoUser", (Object) new Object[] {0L}));
            assertInvocationFailure(
                    "Unknown enum value 0 for demo.GeneratedState",
                    () -> invoke.invoke(null, target, "echoGenerated", (Object) new Object[] {0L}));
            assertEquals(0, intValue(target, "callCount"));

            for (Object invalid : new Object[] {1, null, 0L}) {
                assertThrows(
                        InvocationTargetException.class,
                        () -> setProperty.invoke(null, target, "current", invalid));
            }
            assertEquals(0, intValue(target, "mutationCount"));
            assertEquals(
                    Long.MAX_VALUE,
                    trampoline
                            .getMethod("getProperty", Object.class, String.class)
                            .invoke(null, target, "current"));
        }
    }

    @Test
    void rejectsNullOutboundValuesDeterministically() throws Exception {
        ProcessorCompilation.Result result = ProcessorCompilation.compile(enumSources());

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        URL classes = result.outputDirectory().resolve("classes").toUri().toURL();
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {classes}, getClass().getClassLoader())) {
            Class<?> trampoline = loader.loadClass("demo.EnumCallbacks_FoundryTrampoline");
            Object target = trampoline.getMethod("construct").invoke(null);

            assertInvocationFailure(
                    "Cannot encode null enum demo.UserState",
                    () -> invoke(trampoline, target, "nullUser"));
            assertInvocationFailure(
                    "Cannot encode null enum demo.GeneratedState",
                    () -> invoke(trampoline, target, "nullGenerated"));
        }
    }

    private static Object invoke(
            Class<?> trampoline, Object target, String name, Object... arguments)
            throws ReflectiveOperationException {
        return trampoline
                .getMethod("invoke", Object.class, String.class, Object[].class)
                .invoke(null, target, name, arguments);
    }

    private static int intValue(Object target, String method) throws ReflectiveOperationException {
        return (int) target.getClass().getMethod(method).invoke(target);
    }

    private static void assertInvocationFailure(String message, ThrowingCall call) {
        InvocationTargetException failure =
                assertThrows(InvocationTargetException.class, call::run);
        IllegalArgumentException cause =
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(message, cause.getMessage());
    }

    private static void assertOrdered(String text, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int current = text.indexOf(needle);
            assertTrue(current > previous, () -> "out of order or missing " + needle + "\n" + text);
            previous = current;
        }
    }

    private static Map<String, String> enumSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "demo.UserState",
                """
                package demo;
                import games.cafecito.foundry.annotations.FoundryEnumValue;
                public enum UserState {
                    @FoundryEnumValue(Long.MIN_VALUE) MINIMUM,
                    @FoundryEnumValue(Long.MAX_VALUE) MAXIMUM
                }
                """);
        sources.put(
                "demo.GeneratedState",
                """
                package demo;
                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                @GeneratedByFoundry
                public enum GeneratedState {
                    MINIMUM(Long.MIN_VALUE),
                    MAXIMUM(Long.MAX_VALUE);
                    private final long value;
                    GeneratedState(long value) { this.value = value; }
                    public long value() { return value; }
                    public static GeneratedState fromValue(long value) {
                        if (value == Long.MIN_VALUE) { return MINIMUM; }
                        if (value == Long.MAX_VALUE) { return MAXIMUM; }
                        throw new IllegalArgumentException("generated unknown " + value);
                    }
                }
                """);
        sources.put(
                "demo.SignalOnlyState",
                """
                package demo;
                import games.cafecito.foundry.annotations.FoundryEnumValue;
                public enum SignalOnlyState {
                    @FoundryEnumValue(1) ONLY
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
                "demo.EnumCallbacks",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                public final class EnumCallbacks extends EngineNode {
                    private UserState current = UserState.MAXIMUM;
                    private int calls;
                    private int mutations;
                    @FoundryMethod(name = "echoUser")
                    public UserState echoUser(UserState value) {
                        calls++;
                        return value == UserState.MINIMUM
                                ? UserState.MAXIMUM : UserState.MINIMUM;
                    }
                    @FoundryMethod(name = "echoGenerated")
                    public GeneratedState echoGenerated(GeneratedState value) {
                        calls++;
                        return value == GeneratedState.MINIMUM
                                ? GeneratedState.MAXIMUM : GeneratedState.MINIMUM;
                    }
                    @FoundryMethod(name = "nullUser")
                    public UserState nullUser() { return null; }
                    @FoundryMethod(name = "nullGenerated")
                    public GeneratedState nullGenerated() { return null; }
                    @FoundryOverride
                    public UserState state(UserState value) {
                        calls++;
                        return value;
                    }
                    @FoundryProperty(
                            name = "current", getter = "current", setter = "current")
                    private UserState currentProperty;
                    public UserState current() { return current; }
                    public void current(UserState value) {
                        mutations++;
                        current = value;
                    }
                    public int callCount() { return calls; }
                    public int mutationCount() { return mutations; }
                    @FoundrySignal
                    public interface Changed {
                        void emitted(SignalOnlyState value);
                    }
                }
                """);
        return sources;
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
