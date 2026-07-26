package games.cafecito.foundry.types;

import java.util.Objects;

/** Mutable 64-bit floating-point packed array with copied Java array boundaries. */
public final class PackedFloat64Array implements PackedArray<Double> {
    private final double[] values;

    public PackedFloat64Array(double[] values) {
        this.values = Objects.requireNonNull(values, "values").clone();
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public Double get(int index) {
        return values[FoundryArray.checkIndex(index, size())];
    }

    @Override
    public void set(int index, Double value) {
        values[FoundryArray.checkIndex(index, size())] = Objects.requireNonNull(value, "value");
    }

    public double[] toArray() {
        return values.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PackedFloat64Array array) || values.length != array.values.length) {
            return false;
        }
        for (int index = 0; index < values.length; index++) {
            if (!FoundryFloat.equals(values[index], array.values[index])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (double value : values) {
            result = 31 * result + FoundryFloat.hash(value);
        }
        return result;
    }
}
