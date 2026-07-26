package games.cafecito.foundry.types;

import java.util.Objects;

/** Immutable plane equation. */
public record Plane(Vector3 normal, double d) {
    public Plane {
        Objects.requireNonNull(normal, "normal");
    }
}
