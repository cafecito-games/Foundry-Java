package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the dispatch key the native bridge actually sends into a generated trampoline.
 *
 * <p>The bridge resolves an exported member name to its descriptor and then calls {@code
 * FoundryExtensionAccess} with that descriptor's Java name, so a trampoline keyed on exported names
 * reports "Unknown method" for every member whose Java name differs from its exported name.
 */
class FoundryTrampolineDispatchNameTest {
    private static final String DESCRIPTOR_RESOURCE =
            "META-INF/foundry-java/modules/demo-module.descriptor";

    @Test
    void dispatchesEveryMemberByTheJavaNameTheDescriptorPublishes() throws Exception {
        ProcessorCompilation.Result result = ProcessorCompilation.compile(sources());

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        String descriptor = descriptor(result);
        assertEquals("engine_probe|engineProbe|long(long)", row(descriptor, "method=demo.Probe|"));
        assertTrue(
                row(descriptor, "override=demo.Probe|").startsWith("_state|onState|"), descriptor);
        String property = row(descriptor, "property=demo.Probe|");
        List<String> propertyFields = List.of(property.split("\\|"));
        assertEquals("speed", propertyFields.get(0));
        assertEquals("readSpeed", decode(propertyFields.get(4)));
        assertEquals("writeSpeed", decode(propertyFields.get(5)));

        URL classes = result.outputDirectory().resolve("classes").toUri().toURL();
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {classes}, getClass().getClassLoader())) {
            Class<?> trampoline = loader.loadClass("demo.Probe_FoundryTrampoline");
            Object target = trampoline.getMethod("construct").invoke(null);
            Method invoke =
                    trampoline.getMethod("invoke", Object.class, String.class, Object[].class);
            Method getProperty = trampoline.getMethod("getProperty", Object.class, String.class);
            Method setProperty =
                    trampoline.getMethod("setProperty", Object.class, String.class, Object.class);

            assertEquals(42L, invoke.invoke(null, target, "engineProbe", new Object[] {41L}));
            assertEquals(7L, invoke.invoke(null, target, "onState", new Object[] {6L}));
            setProperty.invoke(null, target, "writeSpeed", 9L);
            assertEquals(9L, getProperty.invoke(null, target, "readSpeed"));

            assertUnknown(
                    "Unknown method: engine_probe",
                    () -> invoke.invoke(null, target, "engine_probe", new Object[] {41L}));
            assertUnknown(
                    "Unknown method: _state",
                    () -> invoke.invoke(null, target, "_state", new Object[] {6L}));
            assertUnknown(
                    "Unknown property: speed", () -> getProperty.invoke(null, target, "speed"));
            assertUnknown(
                    "Unknown property: speed", () -> setProperty.invoke(null, target, "speed", 9L));
        }
    }

    @Test
    void rejectsExportedMembersThatWouldShareOneJavaDispatchName() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(sources());
        sources.put(
                "demo.Probe",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                public final class Probe extends EngineNode {
                    @FoundryMethod(name = "probe_one")
                    public long probe(long value) { return value + 1L; }
                    @FoundryMethod(name = "probe_two")
                    public long probe(long left, long right) { return left + right; }
                }
                """);

        ProcessorCompilation.Result result = ProcessorCompilation.compile(sources);

        assertTrue(
                result.errorMessages().stream()
                        .anyMatch(message -> message.contains("duplicate Java method name probe")),
                () -> result.errorMessages().toString());
    }

    private static String descriptor(ProcessorCompilation.Result result) {
        byte[] bytes = result.classOutput().get(DESCRIPTOR_RESOURCE);
        assertTrue(bytes != null, () -> result.classOutput().keySet().toString());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String row(String descriptor, String prefix) {
        return descriptor
                .lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing descriptor row " + prefix));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void assertUnknown(String message, ThrowingCall call) {
        InvocationTargetException failure =
                assertThrows(InvocationTargetException.class, call::run);
        IllegalArgumentException cause =
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(message, cause.getMessage());
    }

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "demo.EngineNode",
                """
                package demo;
                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                import games.cafecito.foundry.annotations.FoundryVirtual;
                @GeneratedByFoundry
                public class EngineNode {
                    @FoundryVirtual("_state")
                    public long onState(long value) { return value; }
                }
                """);
        sources.put(
                "demo.Probe",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class)
                public final class Probe extends EngineNode {
                    @FoundryProperty(name = "speed", getter = "readSpeed", setter = "writeSpeed")
                    private long speed;
                    public long readSpeed() { return speed; }
                    public void writeSpeed(long value) { speed = value; }
                    @FoundryMethod(name = "engine_probe")
                    public long engineProbe(long value) { return value + 1L; }
                    @FoundryOverride
                    public long onState(long value) { return value + 1L; }
                }
                """);
        return sources;
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }
}
