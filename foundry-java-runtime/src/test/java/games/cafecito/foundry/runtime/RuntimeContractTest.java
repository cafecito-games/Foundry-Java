package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RuntimeContractTest {
    @Test
    void embedsTheAcceptedGenerationAndBridgeContract() {
        assertEquals("1", FoundryRuntime.RUNTIME_CONTRACT_VERSION);
        assertEquals(
                "48af7d0e8fbbbc615d985db39c135402e5120649865cc21e43676da5ee65332b",
                FoundryRuntime.API_SHA256);
        assertEquals(
                "78fe316fd3c02b5b4c452b1cf966040b37f857d18312bcca19d6b2b8b89b021d",
                FoundryRuntime.COMPATIBILITY_MANIFEST_SHA256);
        assertEquals("1", FoundryRuntime.GENERATOR_VERSION);
        assertEquals("1", FoundryRuntime.BRIDGE_CONTRACT_VERSION);
    }

    @Test
    void rejectsTheReservedNullContextHandle() {
        FoundryEngine engine = new NoOpEngine();

        assertThrows(IllegalArgumentException.class, () -> new FoundryBindingContext(0, engine));
    }

    @Test
    void binaryApiGateIncludesGeneratedPublicBindings() throws IOException {
        Path verifier =
                Path.of(System.getProperty("user.dir"))
                        .resolve("../gradle/verify-runtime-api.sh")
                        .normalize();
        String script = Files.readString(verifier);

        assertTrue(script.contains("\"$classes_directory/games/cafecito/foundry\""));
        assertFalse(script.contains("\"$classes_directory/games/cafecito/foundry/runtime\""));
        assertFalse(script.contains("\"$classes_directory/games/cafecito/foundry/types\""));
    }
}
