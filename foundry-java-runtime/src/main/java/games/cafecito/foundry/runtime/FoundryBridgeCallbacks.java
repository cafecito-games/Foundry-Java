package games.cafecito.foundry.runtime;

/** Reentrant Java callback surface consumed by the FoundryExtension/JNI bridge. */
public interface FoundryBridgeCallbacks {
    boolean initialize(long contextHandle, int level);

    void deinitialize(long contextHandle, int level);

    long invoke(long contextHandle, long callbackHandle, long[] argumentHandles);

    void invalidate(long contextHandle);

    /**
     * Reports whether terminal Java cleanup for the requested context has completed.
     *
     * <p>Custom callback implementations that do not retain retryable terminal state may use the
     * default. Coordinators with pending cleanup override this method and remain fail closed.
     */
    default boolean terminalCleanupComplete(long contextHandle) {
        return true;
    }
}
