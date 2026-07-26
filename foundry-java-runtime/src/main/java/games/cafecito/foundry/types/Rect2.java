package games.cafecito.foundry.types;

import java.util.Objects;

/** Immutable floating-point rectangle. */
public record Rect2(Vector2 position, Vector2 size) {
    public Rect2 {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(size, "size");
    }
}
