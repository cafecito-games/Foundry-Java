package games.cafecito.foundry.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FoundryExtensionTest {
    @Test
    void exposesTheStableJavaAbiName() {
        assertEquals("FoundryExtension", FoundryExtension.class.getSimpleName());
    }
}
