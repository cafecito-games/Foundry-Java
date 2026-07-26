package games.cafecito.foundry.runtime;

import games.cafecito.foundry.types.Variant;
import java.util.List;

/** Host-neutral transport implemented by the FoundryExtension/JNI bridge. */
public interface FoundryEngine {
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
