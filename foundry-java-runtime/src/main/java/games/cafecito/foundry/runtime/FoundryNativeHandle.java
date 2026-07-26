package games.cafecito.foundry.runtime;

import java.util.Objects;

/**
 * Opaque bridge-owned handle for a native pointer or structure that is not a Variant value.
 *
 * <p>The numeric handle is meaningful only to the active host bridge; it is never a process
 * address. A zero bridge handle explicitly represents a null native pointer.
 */
public record FoundryNativeHandle(String nativeType, long bridgeHandle) {
    public FoundryNativeHandle {
        if (nativeType == null || nativeType.isBlank()) {
            throw new IllegalArgumentException("Native type must not be blank.");
        }
        Objects.requireNonNull(nativeType, "nativeType");
    }

    public static FoundryNativeHandle nullHandle(String nativeType) {
        return new FoundryNativeHandle(nativeType, 0);
    }

    public boolean isNull() {
        return bridgeHandle == 0;
    }
}
