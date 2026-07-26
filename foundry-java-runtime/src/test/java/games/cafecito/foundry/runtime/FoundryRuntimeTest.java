package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class FoundryRuntimeTest {
    @Test
    void remainsAHostNeutralPlaceholder() {
        assertNotNull(FoundryRuntime.class);
    }
}
