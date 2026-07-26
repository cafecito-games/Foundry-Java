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
                "85e91174c1a8a48629223d6459bb2ef595ad1da405b2ce88435c24fe221aec51",
                FoundryRuntime.API_SHA256);
        assertEquals(
                "1bd2d0bed9e1d7a7bb6fc4dcb6fd0fcb91202e7f468162d8979552c7028fd7e1",
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
