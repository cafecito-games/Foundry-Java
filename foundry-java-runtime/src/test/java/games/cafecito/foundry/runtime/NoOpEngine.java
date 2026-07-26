package games.cafecito.foundry.runtime;

import games.cafecito.foundry.types.Variant;
import java.util.List;

class NoOpEngine implements FoundryEngine {
    @Override
    public CallResult call(
            long contextHandle, long objectHandle, String methodIdentity, List<Variant> arguments) {
        return CallResult.success(Variant.nil());
    }

    @Override
    public Variant decodeVariant(long contextHandle, long variantHandle) {
        return Variant.nil();
    }

    @Override
    public long encodeVariant(long contextHandle, Variant value) {
        return 0;
    }

    @Override
    public boolean isObjectValid(long contextHandle, long objectHandle) {
        return true;
    }

    @Override
    public String objectType(long contextHandle, long objectHandle) {
        return "";
    }

    @Override
    public void retain(long contextHandle, long objectHandle) {}

    @Override
    public void release(long contextHandle, long objectHandle) {}

    @Override
    public long singleton(long contextHandle, String name) {
        return 1;
    }

    @Override
    public void reportCallbackException(
            long contextHandle, long callbackHandle, Throwable failure) {}
}
