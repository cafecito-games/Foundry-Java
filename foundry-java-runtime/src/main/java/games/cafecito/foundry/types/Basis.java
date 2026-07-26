package games.cafecito.foundry.types;

import java.util.Objects;

/** Immutable three-dimensional basis matrix stored as column vectors. */
public record Basis(Vector3 x, Vector3 y, Vector3 z) {
    public Basis {
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(z, "z");
    }
}
