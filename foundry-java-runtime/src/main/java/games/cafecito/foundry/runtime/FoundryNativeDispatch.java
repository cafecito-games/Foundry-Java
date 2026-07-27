package games.cafecito.foundry.runtime;

import java.util.List;
import java.util.Objects;

/** Immutable generated metadata for one native Foundry API dispatch route. */
public record FoundryNativeDispatch(
        String identity,
        Kind kind,
        String ownerNativeType,
        String nativeName,
        long compatibilityHash,
        int constructorIndex,
        List<String> argumentNativeTypes,
        int minimumArgumentCount,
        String returnNativeType,
        String getterIdentity,
        String getterNativeName,
        long getterCompatibilityHash,
        String setterIdentity,
        String setterNativeName,
        long setterCompatibilityHash,
        boolean vararg,
        boolean staticCall) {
    private static final long MAX_COMPATIBILITY_HASH = 0xffff_ffffL;

    public FoundryNativeDispatch {
        identity = requireText(identity, "identity");
        kind = Objects.requireNonNull(kind, "kind");
        ownerNativeType = requireText(ownerNativeType, "ownerNativeType");
        nativeName = requireText(nativeName, "nativeName");
        argumentNativeTypes =
                List.copyOf(Objects.requireNonNull(argumentNativeTypes, "argumentNativeTypes"));
        for (String argumentNativeType : argumentNativeTypes) {
            requireText(argumentNativeType, "argumentNativeTypes entry");
        }
        if (minimumArgumentCount < 0 || minimumArgumentCount > argumentNativeTypes.size()) {
            throw new IllegalArgumentException(
                    "minimumArgumentCount must be between zero and the formal argument count.");
        }
        returnNativeType = requireText(returnNativeType, "returnNativeType");
        getterIdentity = Objects.requireNonNull(getterIdentity, "getterIdentity");
        getterNativeName = Objects.requireNonNull(getterNativeName, "getterNativeName");
        setterIdentity = Objects.requireNonNull(setterIdentity, "setterIdentity");
        setterNativeName = Objects.requireNonNull(setterNativeName, "setterNativeName");
        requireHash(compatibilityHash, "compatibilityHash");
        requireHash(getterCompatibilityHash, "getterCompatibilityHash");
        requireHash(setterCompatibilityHash, "setterCompatibilityHash");
        if (constructorIndex < -1) {
            throw new IllegalArgumentException("constructorIndex must be -1 or nonnegative.");
        }

        boolean getterPresent =
                requireAccessorBundle(
                        getterIdentity,
                        getterNativeName,
                        getterCompatibilityHash,
                        "getter");
        boolean setterPresent =
                requireAccessorBundle(
                        setterIdentity,
                        setterNativeName,
                        setterCompatibilityHash,
                        "setter");
        if (kind == Kind.CLASS_PROPERTY) {
            if (!getterPresent && !setterPresent) {
                throw new IllegalArgumentException(
                        "CLASS_PROPERTY requires a getter or setter accessor.");
            }
            if (compatibilityHash != -1 || constructorIndex != -1 || vararg || staticCall) {
                throw new IllegalArgumentException(
                        "CLASS_PROPERTY cannot declare direct call metadata.");
            }
        } else if (getterPresent || setterPresent) {
            throw new IllegalArgumentException(
                    kind + " cannot declare property accessor metadata.");
        }

        if (kind == Kind.BUILTIN_CONSTRUCTOR) {
            if (constructorIndex < 0 || compatibilityHash != -1 || staticCall) {
                throw new IllegalArgumentException(
                        "BUILTIN_CONSTRUCTOR requires only a nonnegative constructor index.");
            }
        } else if (constructorIndex != -1) {
            throw new IllegalArgumentException(kind + " cannot declare a constructor index.");
        }

        boolean hashed =
                kind == Kind.CLASS_METHOD
                        || kind == Kind.BUILTIN_METHOD
                        || kind == Kind.UTILITY_FUNCTION;
        if (hashed != (compatibilityHash >= 0)) {
            throw new IllegalArgumentException(
                    kind
                            + (hashed
                                    ? " requires a compatibility hash."
                                    : " cannot declare a compatibility hash."));
        }
        if ((kind == Kind.CLASS_SIGNAL
                        || kind == Kind.BUILTIN_MEMBER
                        || kind == Kind.BUILTIN_CONSTANT)
                && (!argumentNativeTypes.isEmpty()
                        || minimumArgumentCount != 0
                        || vararg
                        || staticCall)) {
            throw new IllegalArgumentException(kind + " cannot declare callable arguments.");
        }
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return checked;
    }

    private static void requireHash(long value, String name) {
        if (value < -1 || value > MAX_COMPATIBILITY_HASH) {
            throw new IllegalArgumentException(
                    name + " must be -1 or an unsigned 32-bit value.");
        }
    }

    private static boolean requireAccessorBundle(
            String identity, String nativeName, long compatibilityHash, String name) {
        boolean any = !identity.isEmpty() || !nativeName.isEmpty() || compatibilityHash != -1;
        boolean all = !identity.isBlank() && !nativeName.isBlank() && compatibilityHash >= 0;
        if (any && !all) {
            throw new IllegalArgumentException(name + " accessor metadata must be complete.");
        }
        return all;
    }

    /** Stable wire values shared by generated Java metadata and the native transport. */
    public enum Kind {
        CLASS_METHOD(1),
        CLASS_PROPERTY(2),
        CLASS_SIGNAL(3),
        BUILTIN_METHOD(4),
        BUILTIN_CONSTRUCTOR(5),
        BUILTIN_OPERATOR(6),
        BUILTIN_MEMBER(7),
        BUILTIN_CONSTANT(8),
        UTILITY_FUNCTION(9);

        private final int wireCode;

        Kind(int wireCode) {
            this.wireCode = wireCode;
        }

        public int wireCode() {
            return wireCode;
        }
    }
}
