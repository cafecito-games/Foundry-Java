package games.cafecito.foundry.types;

import java.util.Objects;

/** Immutable Java value for a Foundry NodePath. */
public record NodePath(String value) {
    public NodePath {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
