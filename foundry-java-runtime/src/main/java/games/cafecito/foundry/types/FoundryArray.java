package games.cafecito.foundry.types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Mutable Foundry Array with explicit element typing and reference semantics.
 *
 * <p>The copy constructor creates an alias. Use {@link #duplicate()} or {@link #duplicateDeep()}
 * when independent storage is required.
 */
public final class FoundryArray<T> {
    private final VariantCodec<T> codec;
    private final Storage storage;

    public FoundryArray(VariantCodec<T> codec) {
        this(codec, new Storage());
    }

    public FoundryArray(FoundryArray<T> other) {
        this(Objects.requireNonNull(other, "other").codec, other.storage);
    }

    private FoundryArray(VariantCodec<T> codec, Storage storage) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public static FoundryArray<Variant> untyped() {
        return new FoundryArray<>(VariantCodec.VARIANT);
    }

    public int size() {
        return storage.values.size();
    }

    public boolean isEmpty() {
        return storage.values.isEmpty();
    }

    public void add(T value) {
        addVariant(codec.encode(value));
    }

    public void addVariant(Variant value) {
        Variant checked = Objects.requireNonNull(value, "value");
        codec.decode(checked);
        storage.values.add(checked);
    }

    public T get(int index) {
        return codec.decode(storage.values.get(checkIndex(index, size())));
    }

    public T set(int index, T value) {
        Variant encoded = codec.encode(value);
        codec.decode(encoded);
        Variant previous = storage.values.set(checkIndex(index, size()), encoded);
        return codec.decode(previous);
    }

    public T remove(int index) {
        return codec.decode(storage.values.remove(checkIndex(index, size())));
    }

    public void clear() {
        storage.values.clear();
    }

    public List<T> toList() {
        List<T> copy = new ArrayList<>(size());
        for (Variant value : storage.values) {
            copy.add(codec.decode(value));
        }
        return Collections.unmodifiableList(copy);
    }

    public FoundryArray<T> duplicate() {
        return new FoundryArray<>(codec, new Storage(storage.values));
    }

    public FoundryArray<T> duplicateDeep() {
        List<Variant> values = new ArrayList<>(size());
        for (Variant value : storage.values) {
            values.add(deepDuplicate(value));
        }
        return new FoundryArray<>(codec, new Storage(values));
    }

    private static Variant deepDuplicate(Variant value) {
        Object raw = value.value();
        if (raw instanceof FoundryArray<?> array) {
            return Variant.of(array.duplicateDeep());
        }
        if (raw instanceof FoundryDictionary<?, ?> dictionary) {
            return Variant.of(dictionary.duplicateDeep());
        }
        return value;
    }

    static int checkIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                    "index " + index + " out of bounds for size " + size);
        }
        return index;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof FoundryArray<?> array
                        && storage.values.equals(array.storage.values));
    }

    @Override
    public int hashCode() {
        return storage.values.hashCode();
    }

    @Override
    public String toString() {
        return storage.values.toString();
    }

    private static final class Storage {
        private final ArrayList<Variant> values;

        private Storage() {
            values = new ArrayList<>();
        }

        private Storage(List<Variant> values) {
            this.values = new ArrayList<>(values);
        }
    }
}
