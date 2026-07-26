package games.cafecito.foundry.annotations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.junit.jupiter.api.Test;

class PublicFoundryAbiTest {
    @Test
    void keepsAbiMetadataInClassFiles() {
        assertEquals(
                RetentionPolicy.CLASS,
                PublicFoundryAbi.class.getAnnotation(Retention.class).value());
    }
}
