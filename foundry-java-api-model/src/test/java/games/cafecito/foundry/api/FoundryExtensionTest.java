package games.cafecito.foundry.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FoundryExtensionTest {
    @Test
    void exposesTheStableJavaAbiName() {
        assertEquals("FoundryExtension", FoundryExtension.class.getSimpleName());
    }

    @Test
    void storesThePublicAbiMarkerInTheCompiledClass() throws IOException {
        byte[] classBytes;
        try (var classFile = FoundryExtension.class.getResourceAsStream("FoundryExtension.class")) {
            assertNotNull(classFile);
            classBytes = classFile.readAllBytes();
        }
        String classFileContent = new String(classBytes, StandardCharsets.ISO_8859_1);
        assertTrue(
                classFileContent.contains("Lgames/cafecito/foundry/annotations/PublicFoundryAbi;"));
    }
}
