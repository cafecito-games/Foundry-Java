package games.cafecito.foundry.types;

import java.util.Objects;

/** Immutable two-dimensional affine transform. */
public record Transform2D(Vector2 x, Vector2 y, Vector2 origin) {
    public Transform2D {
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(origin, "origin");
    }
}
