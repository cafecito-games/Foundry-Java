package games.cafecito.foundry.types;

import java.util.Arrays;
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
        return this == other
                || (other instanceof PackedFloat64Array array
                        && Arrays.equals(values, array.values));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}
