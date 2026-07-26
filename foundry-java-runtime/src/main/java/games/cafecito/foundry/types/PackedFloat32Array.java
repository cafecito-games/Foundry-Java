package games.cafecito.foundry.types;

import java.util.Arrays;
import java.util.Objects;

/** Mutable 32-bit floating-point packed array with copied Java array boundaries. */
public final class PackedFloat32Array implements PackedArray<Float> {
    private final float[] values;

    public PackedFloat32Array(float[] values) {
        this.values = Objects.requireNonNull(values, "values").clone();
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public Float get(int index) {
        return values[FoundryArray.checkIndex(index, size())];
    }

    @Override
    public void set(int index, Float value) {
        values[FoundryArray.checkIndex(index, size())] = Objects.requireNonNull(value, "value");
    }

    public float[] toArray() {
        return values.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof PackedFloat32Array array
                        && Arrays.equals(values, array.values));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}
