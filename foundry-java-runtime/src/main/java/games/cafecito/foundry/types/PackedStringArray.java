package games.cafecito.foundry.types;

import java.util.Arrays;
import java.util.Objects;

/** Mutable String packed array with copied Java array boundaries. */
public final class PackedStringArray implements PackedArray<String> {
    private final String[] values;

    public PackedStringArray(String[] values) {
        this.values = checkedCopy(values);
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public String get(int index) {
        return values[FoundryArray.checkIndex(index, size())];
    }

    @Override
    public void set(int index, String value) {
        values[FoundryArray.checkIndex(index, size())] = Objects.requireNonNull(value, "value");
    }

    public String[] toArray() {
        return values.clone();
    }

    private static String[] checkedCopy(String[] source) {
        String[] copy = Objects.requireNonNull(source, "values").clone();
        for (String value : copy) {
            Objects.requireNonNull(value, "packed array element");
        }
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof PackedStringArray array
                        && Arrays.equals(values, array.values));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}
