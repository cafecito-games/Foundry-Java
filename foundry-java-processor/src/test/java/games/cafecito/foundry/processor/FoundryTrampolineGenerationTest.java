package games.cafecito.foundry.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FoundryTrampolineGenerationTest {
    @Test
    void generatesACompilableDirectCallTrampoline() throws IOException {
        ProcessorCompilation.Result result = ProcessorCompilation.compile(extensionSources());

        assertTrue(result.successful(), () -> result.errorMessages().toString());
        String path = "demo/SpinningCube_FoundryTrampoline.java";
        assertEquals(
                golden("SpinningCube_FoundryTrampoline.golden"),
                result.generatedSources().get(path));
        assertTrue(
                result.classOutput().containsKey("demo/SpinningCube_FoundryTrampoline.class"),
                result.classOutput().keySet().toString());
        String generated = result.generatedSources().get(path);
        for (String forbidden :
                new String[] {
                    "java.lang.reflect",
                    "Class.forName",
                    "getDeclaredMethod",
                    "getDeclaredMethods",
                    "ServiceLoader"
                }) {
            assertFalse(generated.contains(forbidden), forbidden);
        }
    }

    static Map<String, String> extensionSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "demo.EngineNode",
                """
                package demo;
                public class EngineNode {
                    public void _process(double delta) {}
                }
                """);
        sources.put(
                "demo.SpinningCube",
                """
                package demo;
                import games.cafecito.foundry.annotations.*;
                @FoundryClass(base = EngineNode.class, name = "SpinningCube")
                @FoundryInitialization(InitializationLevel.SCENE)
                public final class SpinningCube extends EngineNode {
                    @FoundryProperty(name = "speed", getter = "speed", setter = "speed")
                    private double speed;
                    public double speed() { return speed; }
                    public void speed(double value) { speed = value; }
                    @FoundryMethod public void reset() { speed = 0.0; }
                    @FoundryOverride public void _process(double delta) { speed += delta; }
                    @FoundrySignal(name = "reset_done")
                    public interface Reset {
                        void emitted(double previousSpeed);
                    }
                }
                """);
        return sources;
    }

    static String golden(String name) throws IOException {
        try (var stream =
                FoundryTrampolineGenerationTest.class.getResourceAsStream("/golden/" + name)) {
            if (stream == null) {
                throw new IOException("missing golden resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
