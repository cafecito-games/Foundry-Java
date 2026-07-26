package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.api.FoundryExtension;
import org.junit.jupiter.api.Test;

class FoundryRuntimeTest {
    @Test
    void attachesThroughThePublicExtensionAbi() {
        boolean[] attached = {false};
        FoundryRuntime.attach(
                new FoundryExtension() {
                    @Override
                    public void onAttached() {
                        attached[0] = true;
                    }
                });
        assertTrue(attached[0]);
    }
}
