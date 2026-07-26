package games.cafecito.foundry.types;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Mutable insertion-ordered Foundry Dictionary with reference semantics.
 *
 * <p>The copy constructor creates an alias. Equality and hashing follow dictionary content rather
 * than insertion order.
 */
public final class FoundryDictionary<K, V> {
    private final VariantCodec<K> keyCodec;
    private final VariantCodec<V> valueCodec;
    private final Storage storage;

    public FoundryDictionary(VariantCodec<K> keyCodec, VariantCodec<V> valueCodec) {
        this(keyCodec, valueCodec, new Storage());
    }

    public FoundryDictionary(FoundryDictionary<K, V> other) {
        this(Objects.requireNonNull(other, "other").keyCodec, other.valueCodec, other.storage);
    }

    private FoundryDictionary(
            VariantCodec<K> keyCodec, VariantCodec<V> valueCodec, Storage storage) {
        this.keyCodec = Objects.requireNonNull(keyCodec, "keyCodec");
        this.valueCodec = Objects.requireNonNull(valueCodec, "valueCodec");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public int size() {
        return storage.values.size();
    }

    public boolean isEmpty() {
        return storage.values.isEmpty();
    }

    public V put(K key, V value) {
        Variant encodedKey = keyCodec.encode(key);
        Variant encodedValue = valueCodec.encode(value);
        keyCodec.decode(encodedKey);
        valueCodec.decode(encodedValue);
        Variant previous = storage.values.put(encodedKey, encodedValue);
        return previous == null ? null : valueCodec.decode(previous);
    }

    public void putVariants(Variant key, Variant value) {
        Variant checkedKey = Objects.requireNonNull(key, "key");
        Variant checkedValue = Objects.requireNonNull(value, "value");
        keyCodec.decode(checkedKey);
        valueCodec.decode(checkedValue);
        storage.values.put(checkedKey, checkedValue);
    }

    public V get(K key) {
        Variant encodedKey = keyCodec.encode(key);
        keyCodec.decode(encodedKey);
        Variant value = storage.values.get(encodedKey);
        return value == null ? null : valueCodec.decode(value);
    }

    public boolean containsKey(K key) {
        Variant encodedKey = keyCodec.encode(key);
        keyCodec.decode(encodedKey);
        return storage.values.containsKey(encodedKey);
    }

    public V remove(K key) {
        Variant encodedKey = keyCodec.encode(key);
        keyCodec.decode(encodedKey);
        Variant previous = storage.values.remove(encodedKey);
        return previous == null ? null : valueCodec.decode(previous);
    }

    public void clear() {
        storage.values.clear();
    }

    public Map<K, V> toMap() {
        LinkedHashMap<K, V> copy = new LinkedHashMap<>();
        storage.values.forEach(
                (key, value) -> copy.put(keyCodec.decode(key), valueCodec.decode(value)));
        return Collections.unmodifiableMap(copy);
    }

    public FoundryDictionary<K, V> duplicate() {
        return new FoundryDictionary<>(keyCodec, valueCodec, new Storage(storage.values));
    }

    public FoundryDictionary<K, V> duplicateDeep() {
        LinkedHashMap<Variant, Variant> values = new LinkedHashMap<>();
        storage.values.forEach(
                (key, value) -> values.put(deepDuplicate(key), deepDuplicate(value)));
        return new FoundryDictionary<>(keyCodec, valueCodec, new Storage(values));
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

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof FoundryDictionary<?, ?> dictionary
                        && storage.values.equals(dictionary.storage.values));
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
        private final LinkedHashMap<Variant, Variant> values;

        private Storage() {
            values = new LinkedHashMap<>();
        }

        private Storage(Map<Variant, Variant> values) {
            this.values = new LinkedHashMap<>(values);
        }
    }
}
