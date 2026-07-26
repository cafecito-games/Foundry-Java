package games.cafecito.foundry.types;

import java.util.Objects;

/** Immutable four-by-four projection matrix stored as column vectors. */
public record Projection(Vector4 x, Vector4 y, Vector4 z, Vector4 w) {
    public Projection {
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(z, "z");
        Objects.requireNonNull(w, "w");
    }
}
