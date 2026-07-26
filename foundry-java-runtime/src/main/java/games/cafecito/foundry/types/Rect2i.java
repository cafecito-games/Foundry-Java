package games.cafecito.foundry.types;

import java.util.Objects;

/** Immutable integer rectangle. */
public record Rect2i(Vector2i position, Vector2i size) {
    public Rect2i {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(size, "size");
    }
}
