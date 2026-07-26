package games.cafecito.foundry.types;

import java.util.Objects;

/** Immutable plane equation. */
public record Plane(Vector3 normal, double d) {
    public Plane {
        Objects.requireNonNull(normal, "normal");
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof Plane value
                        && normal.equals(value.normal)
                        && FoundryFloat.equals(d, value.d));
    }

    @Override
    public int hashCode() {
        return 31 * normal.hashCode() + FoundryFloat.hash(d);
    }
}
