package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FoundryModuleGenerationTest {
    @Test
    void emitsOneGoldenRegistryDescriptorAndNarrowKeepFile() throws IOException {
        ProcessorCompilation.Result result =
                ProcessorCompilation.compile(FoundryTrampolineGenerationTest.extensionSources());

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
                        "api_sha256=85e91174c1a8a48629223d6459bb2ef595ad1da405b2ce88435c24fe221aec51\n"));
        assertTrue(descriptor.contains("generator_version=1\n"));
        assertTrue(descriptor.contains("runtime_contract_version=1\n"));
        assertTrue(descriptor.contains("bridge_contract_version=1\n"));
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
    void sourceOrderCannotChangeGeneratedOutputs() throws IOException {
        Map<String, String> forward = FoundryTrampolineGenerationTest.extensionSources();
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
}
