package games.cafecito.foundry.types;

import java.util.Arrays;
import java.util.Objects;

/** Mutable 64-bit integer packed array with copied Java array boundaries. */
public final class PackedInt64Array implements PackedArray<Long> {
    private final long[] values;

    public PackedInt64Array(long[] values) {
        this.values = Objects.requireNonNull(values, "values").clone();
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public Long get(int index) {
        return values[FoundryArray.checkIndex(index, size())];
    }

    @Override
    public void set(int index, Long value) {
        values[FoundryArray.checkIndex(index, size())] = Objects.requireNonNull(value, "value");
    }

    public long[] toArray() {
        return values.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof PackedInt64Array array && Arrays.equals(values, array.values));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}
