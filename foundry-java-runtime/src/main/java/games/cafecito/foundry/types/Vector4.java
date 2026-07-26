package games.cafecito.foundry.types;

/** Immutable four-dimensional floating-point vector. */
public record Vector4(double x, double y, double z, double w) {
    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof Vector4 value
                        && FoundryFloat.equals(x, value.x)
                        && FoundryFloat.equals(y, value.y)
                        && FoundryFloat.equals(z, value.z)
                        && FoundryFloat.equals(w, value.w));
    }

    @Override
    public int hashCode() {
        int result = FoundryFloat.hash(x);
        result = 31 * result + FoundryFloat.hash(y);
        result = 31 * result + FoundryFloat.hash(z);
        return 31 * result + FoundryFloat.hash(w);
    }
}
