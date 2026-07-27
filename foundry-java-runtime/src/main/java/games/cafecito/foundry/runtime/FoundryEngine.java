package games.cafecito.foundry.runtime;

import games.cafecito.foundry.types.Variant;
import java.util.List;

/** Host-neutral transport implemented by the FoundryExtension/JNI bridge. */
public interface FoundryEngine {
    /** Registers one already validated generated extension-class descriptor. */
    void registerExtensionClass(long contextHandle, FoundryClassDescriptor descriptor);

    /** Unregisters one generated extension class while the native interface is still live. */
    void unregisterExtensionClass(long contextHandle, String foundryName);

    CallResult call(
            long contextHandle, long objectHandle, String methodIdentity, List<Variant> arguments);

    Variant decodeVariant(long contextHandle, long variantHandle);

    long encodeVariant(long contextHandle, Variant value);

    boolean isObjectValid(long contextHandle, long objectHandle);

    /**
     * Returns the most-derived registered Foundry class name for an object handle.
     *
     * <p>The host bridge must return an empty string only when type information is unavailable.
     */
    String objectType(long contextHandle, long objectHandle);

    /** Instantiates one schema-declared instantiable engine class. */
    long instantiate(long contextHandle, String className);

    void retain(long contextHandle, long objectHandle);

    void release(long contextHandle, long objectHandle);

    long singleton(long contextHandle, String name);

    void reportCallbackException(long contextHandle, long callbackHandle, Throwable failure);

    record CallResult(
            Variant value, FoundryCallError error, int argumentIndex, String expectedType) {
        public static CallResult success(Variant value) {
            return new CallResult(value, FoundryCallError.OK, -1, "");
        }
    }
}
