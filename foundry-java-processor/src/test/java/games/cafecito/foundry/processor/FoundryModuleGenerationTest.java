package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FoundryModuleGenerationTest {
    @Test
    void emitsOneGoldenRegistryDescriptorAndNarrowKeepFile() throws IOException {
        ProcessorCompilation.Result result = ProcessorCompilation.compile(enumExtensionSources());

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        String registry =
                result.generatedSources()
                        .get(
                                "games/cafecito/foundry/generated/demomodule/"
                                        + "DemoModuleRegistry.java");
        assertTrue(
                registry.contains(
                        "implements games.cafecito.foundry.runtime.FoundryModuleProvider"));
        assertTrue(
                registry.contains(
                        "public static final games.cafecito.foundry.runtime.FoundryModuleProvider"
                                + " PROVIDER"));
        assertTrue(
                registry.contains(
                        "games.cafecito.foundry.runtime.FoundryModuleDescriptor descriptor()"));
        assertEquals(FoundryTrampolineGenerationTest.golden("DemoModuleRegistry.golden"), registry);
        assertTrue(
                registry.contains("demo.SpinningCube_FoundryTrampoline.construct(context, lease)"));
        assertTrue(registry.contains("demo.SpinningCube_FoundryTrampoline.invoke("));
        assertResource(
                result,
                "META-INF/foundry-java/modules/demo-module.descriptor",
                "demo-module.descriptor");
        String descriptor =
                new String(
                        result.classOutput()
                                .get("META-INF/foundry-java/modules/demo-module.descriptor"),
                        StandardCharsets.UTF_8);
        assertTrue(descriptor.startsWith("format=2\n"));
        assertTrue(
                descriptor.contains(
                        "api_sha256=48af7d0e8fbbbc615d985db39c135402e5120649865cc21e43676da5ee65332b\n"));
        assertTrue(descriptor.contains("generator_version=1\n"));
        assertTrue(descriptor.contains("runtime_contract_version=1\n"));
        assertTrue(descriptor.contains("bridge_contract_version=1\n"));
        assertEquals(8, descriptorRow(descriptor, "constant=").split("\\|", -1).length);
        assertEquals(12, descriptorRow(descriptor, "property=").split("\\|", -1).length);
        assertTrue(
                descriptor.contains(
                        "constant=demo.SpinningCube|min_value|MIN_VALUE|long|d1|"
                                + "TW92ZW1lbnRNb2Rl|-9223372036854775808|1\n"),
                descriptor);
        assertTrue(
                descriptor.contains(
                        "property=demo.SpinningCube|speed|speed|double|d1|c3BlZWQ|c3BlZWQ|7|"
                                + "TW90aW9u|bW90aW9uXw|U3BlZWQ|c3BlZWRf\n"),
                descriptor);
        assertEquals(
                List.of(
                        "constant",
                        "method",
                        "method",
                        "override",
                        "override",
                        "property",
                        "property",
                        "signal",
                        "signal"),
                descriptor
                        .lines()
                        .filter(
                                line ->
                                        line.startsWith("constant=")
                                                || line.startsWith("method=")
                                                || line.startsWith("override=")
                                                || line.startsWith("property=")
                                                || line.startsWith("signal="))
                        .map(line -> line.substring(0, line.indexOf('=')))
                        .toList());
        assertResource(
                result,
                "META-INF/proguard/foundry-java-demo-module.pro",
                "foundry-java-demo-module.pro");
        String keep =
                new String(
                        result.classOutput().get("META-INF/proguard/foundry-java-demo-module.pro"),
                        StandardCharsets.UTF_8);
        assertTrue(
                keep.lines()
                        .filter(line -> line.startsWith("-keep class "))
                        .map(line -> line.substring("-keep class ".length(), line.indexOf(" {")))
                        .noneMatch(className -> className.contains("*")));
        assertFalse(keep.contains("games.cafecito.foundry.**"));
    }

    @Test
    void metadataEncodingRoundTripsDelimitersNewlinesBackslashesAndUnicode() throws IOException {
        Map<String, String> sources =
                new LinkedHashMap<>(FoundryTrampolineGenerationTest.extensionSources());
        sources.computeIfPresent(
                "demo.SpinningCube",
                (name, source) ->
                        source.replace("\"MovementMode\"", javaLiteral("Mode|Line\n\\\u96ea"))
                                .replace("\"Motion\"", javaLiteral("Group|Line\n\\\u96ea"))
                                .replace("\"motion_\"", javaLiteral("prefix|\\\u96ea"))
                                .replace("\"Speed\"", javaLiteral("Subgroup|Line\n\\\u96ea"))
                                .replace("\"speed_\"", javaLiteral("sub|\\\u96ea")));

        ProcessorCompilation.Result result = ProcessorCompilation.compile(sources);

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        String descriptor =
                new String(
                        result.classOutput()
                                .get("META-INF/foundry-java/modules/demo-module.descriptor"),
                        StandardCharsets.UTF_8);
        String[] constant = descriptorRow(descriptor, "constant=").split("\\|", -1);
        String[] property = descriptorRow(descriptor, "property=").split("\\|", -1);
        assertEquals("Mode|Line\n\\\u96ea", decode(constant[5]));
        assertEquals("Group|Line\n\\\u96ea", decode(property[8]));
        assertEquals("prefix|\\\u96ea", decode(property[9]));
        assertEquals("Subgroup|Line\n\\\u96ea", decode(property[10]));
        assertEquals("sub|\\\u96ea", decode(property[11]));
        for (int index : new int[] {5}) {
            assertFalse(constant[index].contains("="), constant[index]);
        }
        for (int index : new int[] {5, 6, 8, 9, 10, 11}) {
            assertFalse(property[index].contains("="), property[index]);
        }
        String registry =
                result.generatedSources()
                        .get(
                                "games/cafecito/foundry/generated/demomodule/"
                                        + "DemoModuleRegistry.java");
        assertEquals(
                1,
                registry.lines()
                        .filter(line -> line.contains("private static String decode("))
                        .count());
    }

    @Test
    void serializesEnumsAsLongAcrossEveryTransportPosition() throws IOException {
        ProcessorCompilation.Result result = ProcessorCompilation.compile(enumExtensionSources());

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        String descriptor =
                new String(
                        result.classOutput()
                                .get("META-INF/foundry-java/modules/demo-module.descriptor"),
                        StandardCharsets.UTF_8);
        assertTrue(
                descriptor.contains(
                        "method=demo.SpinningCube|convert_mode|convertMode|long(long,long)\n"),
                descriptor);
        assertTrue(
                descriptor.contains(
                        "override=demo.SpinningCube|_engine_mode|onEngineMode|long(long)\n"),
                descriptor);
        assertTrue(
                descriptor.contains(
                        "property=demo.SpinningCube|movement_mode|movementMode|long|d1|"
                                + "bW92ZW1lbnRNb2Rl|bW92ZW1lbnRNb2Rl|-1||||\n"),
                descriptor);
        assertTrue(
                descriptor.contains(
                        "signal=demo.SpinningCube|mode_changed|ModeChanged|void(long,long)\n"),
                descriptor);
        assertTrue(
                descriptor.contains(
                        "constant=demo.SpinningCube|min_value|MIN_VALUE|long|d1|"
                                + "TW92ZW1lbnRNb2Rl|-9223372036854775808|1\n"),
                descriptor);
        assertFalse(descriptor.contains("demo.EngineMode("), descriptor);
        assertFalse(descriptor.contains("demo.UserMode("), descriptor);

        String registry =
                result.generatedSources()
                        .get(
                                "games/cafecito/foundry/generated/demomodule/"
                                        + "DemoModuleRegistry.java");
        for (String signature :
                List.of(
                        "\"long(long,long)\"",
                        "\"long(long)\"",
                        "\"long\"",
                        "\"void(long,long)\"")) {
            assertTrue(registry.contains(signature), registry);
        }
    }

    @Test
    void sourceOrderCannotChangeGeneratedOutputs() throws IOException {
        Map<String, String> forward = enumExtensionSources();
        Map<String, String> reverse = new LinkedHashMap<>();
        forward.entrySet().stream()
                .sorted(Map.Entry.<String, String>comparingByKey().reversed())
                .forEach(entry -> reverse.put(entry.getKey(), entry.getValue()));

        ProcessorCompilation.Result first = ProcessorCompilation.compile(forward);
        ProcessorCompilation.Result second = ProcessorCompilation.compile(reverse);

        assertTrue(first.successful(), () -> first.errorMessages().toString());
        assertTrue(second.successful(), () -> second.errorMessages().toString());
        assertNotEquals(first.inputOrder(), second.inputOrder());
        assertEquals(first.generatedSources(), second.generatedSources());
        assertEquals(first.classOutput().keySet(), second.classOutput().keySet());
        first.classOutput()
                .forEach(
                        (path, bytes) ->
                                assertArrayEquals(bytes, second.classOutput().get(path), path));
    }

    private static void assertResource(
            ProcessorCompilation.Result result, String path, String golden) throws IOException {
        byte[] actual = result.classOutput().get(path);
        assertTrue(
                actual != null, () -> "missing " + path + " in " + result.classOutput().keySet());
        assertArrayEquals(
                FoundryTrampolineGenerationTest.golden(golden).getBytes(StandardCharsets.UTF_8),
                actual);
    }

    private static String descriptorRow(String descriptor, String prefix) {
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

    private static String javaLiteral(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\"";
    }

    private static Map<String, String> enumExtensionSources() {
        Map<String, String> sources =
                new LinkedHashMap<>(FoundryTrampolineGenerationTest.extensionSources());
        sources.put(
                "demo.EngineMode",
                """
                package demo;
                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                @GeneratedByFoundry
                public enum EngineMode {
                    IDLE(-7L),
                    ACTIVE(Long.MAX_VALUE);
                    private final long value;
                    EngineMode(long value) { this.value = value; }
                    public long value() { return value; }
                    public static EngineMode fromValue(long value) {
                        for (EngineMode candidate : values()) {
                            if (candidate.value == value) {
                                return candidate;
                            }
                        }
                        throw new IllegalArgumentException("Unknown EngineMode value " + value + ".");
                    }
                }
                """);
        sources.put(
                "demo.UserMode",
                """
                package demo;
                import games.cafecito.foundry.annotations.FoundryEnumValue;
                public enum UserMode {
                    @FoundryEnumValue(value = Long.MIN_VALUE)
                    IDLE,
                    @FoundryEnumValue(value = Long.MAX_VALUE)
                    ACTIVE
                }
                """);
        sources.computeIfPresent(
                "demo.EngineNode",
                (name, source) ->
                        source.replace(
                                "    @FoundryVirtual(\"_process\")\n"
                                        + "    protected void onProcess(double delta) {}\n",
                                "    @FoundryVirtual(\"_engine_mode\")\n"
                                        + "    protected EngineMode onEngineMode(EngineMode mode) { return mode; }\n"
                                        + "    @FoundryVirtual(\"_process\")\n"
                                        + "    protected void onProcess(double delta) {}\n"));
        sources.computeIfPresent(
                "demo.SpinningCube",
                (name, source) ->
                        source.replace(
                                "    @FoundryMethod public void reset() { speed = 0.0; }\n"
                                        + "    @FoundryOverride public void onProcess(double delta) { speed += delta; }\n",
                                "    @FoundryProperty(\n"
                                        + "            name = \"movement_mode\",\n"
                                        + "            getter = \"movementMode\",\n"
                                        + "            setter = \"movementMode\")\n"
                                        + "    private UserMode movementMode = UserMode.IDLE;\n"
                                        + "    public UserMode movementMode() { return movementMode; }\n"
                                        + "    public void movementMode(UserMode value) { movementMode = value; }\n"
                                        + "    @FoundryMethod(name = \"convert_mode\")\n"
                                        + "    public UserMode convertMode(EngineMode engineMode, UserMode userMode) {\n"
                                        + "        return userMode;\n"
                                        + "    }\n"
                                        + "    @FoundryMethod public void reset() { speed = 0.0; }\n"
                                        + "    @FoundryOverride\n"
                                        + "    public EngineMode onEngineMode(EngineMode mode) { return mode; }\n"
                                        + "    @FoundryOverride public void onProcess(double delta) { speed += delta; }\n"
                                        + "    @FoundrySignal(name = \"mode_changed\")\n"
                                        + "    public interface ModeChanged {\n"
                                        + "        void emitted(EngineMode engineMode, UserMode userMode);\n"
                                        + "    }\n"));
        return sources;
    }
}
