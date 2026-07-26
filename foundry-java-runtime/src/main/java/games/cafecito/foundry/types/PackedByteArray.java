package games.cafecito.foundry.types;

import java.util.Arrays;
import java.util.Objects;

/** Mutable byte packed array with copied Java array boundaries. */
public final class PackedByteArray implements PackedArray<Byte> {
    private final byte[] values;

    public PackedByteArray(byte[] values) {
        this.values = Objects.requireNonNull(values, "values").clone();
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public Byte get(int index) {
        return values[FoundryArray.checkIndex(index, size())];
    }

    @Override
    public void set(int index, Byte value) {
        values[FoundryArray.checkIndex(index, size())] = Objects.requireNonNull(value, "value");
    }

    public byte[] toArray() {
        return values.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof PackedByteArray array && Arrays.equals(values, array.values));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}
