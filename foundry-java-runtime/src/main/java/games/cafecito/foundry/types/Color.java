package games.cafecito.foundry.types;

/** Immutable linear RGBA color value. */
public record Color(double red, double green, double blue, double alpha) {
    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof Color value
                        && FoundryFloat.equals(red, value.red)
                        && FoundryFloat.equals(green, value.green)
                        && FoundryFloat.equals(blue, value.blue)
                        && FoundryFloat.equals(alpha, value.alpha));
    }

    @Override
    public int hashCode() {
        int result = FoundryFloat.hash(red);
        result = 31 * result + FoundryFloat.hash(green);
        result = 31 * result + FoundryFloat.hash(blue);
        return 31 * result + FoundryFloat.hash(alpha);
    }
}
