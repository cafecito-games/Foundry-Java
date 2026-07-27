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
        assertTrue(
                generated.contains(
                        "construct(\n"
                                + "            games.cafecito.foundry.runtime.FoundryBindingContext context,\n"
                                + "            games.cafecito.foundry.runtime.ObjectLease lease)"),
                generated);
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
                import games.cafecito.foundry.annotations.GeneratedByFoundry;
                import games.cafecito.foundry.annotations.FoundryVirtual;
                import games.cafecito.foundry.runtime.FoundryBindingContext;
                import games.cafecito.foundry.runtime.FoundryObject;
                import games.cafecito.foundry.runtime.ObjectLease;
                @GeneratedByFoundry
                public class EngineNode extends FoundryObject {
                    protected EngineNode(FoundryBindingContext context, ObjectLease lease) {
                        super(context, lease);
                    }
                    @FoundryVirtual("_process")
                    protected void onProcess(double delta) {}
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
                    public SpinningCube(
                            games.cafecito.foundry.runtime.FoundryBindingContext context,
                            games.cafecito.foundry.runtime.ObjectLease lease) {
                        super(context, lease);
                    }
                    @FoundryConstant(
                            name = "min_value",
                            enumName = "MovementMode",
                            bitfield = true)
                    public static final long MIN_VALUE = Long.MIN_VALUE;
                    @FoundryProperty(
                            name = "speed",
                            getter = "speed",
                            setter = "speed",
                            index = 7,
                            groupName = "Motion",
                            groupPrefix = "motion_",
                            subgroupName = "Speed",
                            subgroupPrefix = "speed_")
                    private double speed;
                    public double speed() { return speed; }
                    public void speed(double value) { speed = value; }
                    @FoundryMethod public void reset() { speed = 0.0; }
                    @FoundryOverride public void onProcess(double delta) { speed += delta; }
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
