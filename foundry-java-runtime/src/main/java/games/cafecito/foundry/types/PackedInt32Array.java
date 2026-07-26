package games.cafecito.foundry.types;

import java.util.Arrays;
import java.util.Objects;

/** Mutable 32-bit integer packed array with copied Java array boundaries. */
public final class PackedInt32Array implements PackedArray<Integer> {
    private final int[] values;

    public PackedInt32Array(int[] values) {
        this.values = Objects.requireNonNull(values, "values").clone();
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public Integer get(int index) {
        return values[FoundryArray.checkIndex(index, size())];
    }

    @Override
    public void set(int index, Integer value) {
        values[FoundryArray.checkIndex(index, size())] = Objects.requireNonNull(value, "value");
    }

    public int[] toArray() {
        return values.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof PackedInt32Array array && Arrays.equals(values, array.values));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}
