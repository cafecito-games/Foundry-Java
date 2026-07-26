package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
