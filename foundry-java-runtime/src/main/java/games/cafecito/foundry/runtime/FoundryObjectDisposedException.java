package games.cafecito.foundry.runtime;

/** Raised when Java code uses an object after close, invalidation, or context shutdown. */
public final class FoundryObjectDisposedException extends IllegalStateException {
    FoundryObjectDisposedException(long contextHandle, long objectHandle) {
        super(
                "Foundry object "
                        + objectHandle
                        + " in context "
                        + contextHandle
                        + " is no longer alive.");
    }
}
