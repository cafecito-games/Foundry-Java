package games.cafecito.foundry.types;

import java.util.Objects;

/** Immutable Java value for a Foundry StringName. */
public record StringName(String value) {
    public StringName {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
