package games.cafecito.foundry.types;

/** Foundry floating-point equality and hashing shared by Variant value types. */
final class FoundryFloat {
    private FoundryFloat() {}

    static boolean equals(double left, double right) {
        return left == right || (Double.isNaN(left) && Double.isNaN(right));
    }

    static int hash(double value) {
        double normalized = Double.isNaN(value) ? Double.NaN : (value == 0.0d ? 0.0d : value);
        return Double.hashCode(normalized);
    }

    static boolean equals(float left, float right) {
        return left == right || (Float.isNaN(left) && Float.isNaN(right));
    }

    static int hash(float value) {
        float normalized = Float.isNaN(value) ? Float.NaN : (value == 0.0f ? 0.0f : value);
        return Float.hashCode(normalized);
    }
}
