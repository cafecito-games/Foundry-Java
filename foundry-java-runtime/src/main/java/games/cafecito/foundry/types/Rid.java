package games.cafecito.foundry.types;

/** Immutable opaque Foundry resource identifier. Zero represents an invalid RID. */
public record Rid(long id) {
    public Rid {
        if (id < 0) {
            throw new IllegalArgumentException("RID must be nonnegative: " + id);
        }
    }
}
