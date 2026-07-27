package games.cafecito.foundry.runtime;

import games.cafecito.foundry.types.Variant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Context-local registry that prevents new callbacks once shutdown begins. */
public final class CallbackRegistry {
    private final FoundryBindingContext context;
    private final Object lifecycle = new Object();
    private final Map<Long, FoundryCallable> callbacks = new HashMap<>();
    private final ThreadLocal<Integer> invocationDepth = ThreadLocal.withInitial(() -> 0);
    private long nextHandle;
    private int activeInvocations;
    private volatile boolean enabled = true;

    CallbackRegistry(FoundryBindingContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public long register(FoundryCallable callable) {
        synchronized (lifecycle) {
            requireEnabled();
            long handle = ++nextHandle;
            callbacks.put(handle, Objects.requireNonNull(callable, "callable"));
            return handle;
        }
    }

    public boolean unregister(long callbackHandle) {
        synchronized (lifecycle) {
            return callbacks.remove(callbackHandle) != null;
        }
    }

    public Variant invoke(long callbackHandle, List<Variant> arguments) {
        FoundryCallable callable = beginInvocation(callbackHandle);
        try {
            return callable.call(arguments);
        } finally {
            endInvocation();
        }
    }

    long invokeBridge(long callbackHandle, long[] argumentHandles) {
        FoundryCallable callable = beginInvocation(callbackHandle);
        try {
            FoundryEngine engine = context.engine();
            List<Variant> arguments = new ArrayList<>(argumentHandles.length);
            for (long argumentHandle : argumentHandles) {
                arguments.add(
                        Objects.requireNonNull(
                                engine.decodeVariant(context.contextHandle(), argumentHandle),
                                "decoded callback argument"));
            }
            Variant result = callable.call(arguments);
            if (!context.isAlive()) {
                return 0;
            }
            return engine.encodeVariant(context.contextHandle(), result);
        } finally {
            endInvocation();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    boolean disableAndDrain() {
        boolean interrupted = false;
        synchronized (lifecycle) {
            if (enabled) {
                enabled = false;
                callbacks.clear();
            }
            int callerInvocations = invocationDepth.get();
            while (activeInvocations > callerInvocations) {
                try {
                    lifecycle.wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (lifecycle) {
            return activeInvocations == 0;
        }
    }

    private FoundryCallable beginInvocation(long callbackHandle) {
        synchronized (lifecycle) {
            requireEnabled();
            FoundryCallable callable = callbacks.get(callbackHandle);
            if (callable == null) {
                throw new CallbackUnavailableException();
            }
            activeInvocations++;
            invocationDepth.set(invocationDepth.get() + 1);
            return callable;
        }
    }

    private void endInvocation() {
        synchronized (lifecycle) {
            int remainingDepth = invocationDepth.get() - 1;
            if (remainingDepth == 0) {
                invocationDepth.remove();
            } else {
                invocationDepth.set(remainingDepth);
            }
            activeInvocations--;
            lifecycle.notifyAll();
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new CallbackUnavailableException();
        }
    }

    static final class CallbackUnavailableException extends IllegalStateException {}
}
