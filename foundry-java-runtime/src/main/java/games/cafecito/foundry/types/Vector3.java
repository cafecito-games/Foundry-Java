package games.cafecito.foundry.types;

/** Immutable three-dimensional floating-point vector. */
public record Vector3(double x, double y, double z) {
    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof Vector3 value
                        && FoundryFloat.equals(x, value.x)
                        && FoundryFloat.equals(y, value.y)
                        && FoundryFloat.equals(z, value.z));
    }

    @Override
    public int hashCode() {
        return 31 * (31 * FoundryFloat.hash(x) + FoundryFloat.hash(y)) + FoundryFloat.hash(z);
    }
}
