package games.cafecito.foundry.types;

/** Immutable two-dimensional floating-point vector. */
public record Vector2(double x, double y) {
    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof Vector2 value
                        && FoundryFloat.equals(x, value.x)
                        && FoundryFloat.equals(y, value.y));
    }

    @Override
    public int hashCode() {
        return 31 * FoundryFloat.hash(x) + FoundryFloat.hash(y);
    }
}
