package games.cafecito.foundry.runtime;

/** Reentrant Java callback surface consumed by the FoundryExtension/JNI bridge. */
public interface FoundryBridgeCallbacks {
    boolean initialize(long contextHandle, int level);

    void deinitialize(long contextHandle, int level);

    long invoke(long contextHandle, long callbackHandle, long[] argumentHandles);

    void invalidate(long contextHandle);
}
