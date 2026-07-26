package games.cafecito.foundry.runtime;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java implementation of the callback surface invoked by the WS7 FoundryExtension bridge.
 *
 * <p>Every method permits same-thread reentrancy. No Java throwable crosses this boundary.
 */
public final class FoundryRuntimeCallbacks implements FoundryBridgeCallbacks {
    private final ConcurrentHashMap<Long, ContextState> contexts = new ConcurrentHashMap<>();

    public void register(FoundryBindingContext context) {
        Objects.requireNonNull(context, "context");
        ContextState state = new ContextState(context);
        ContextState previous = contexts.putIfAbsent(context.contextHandle(), state);
        if (previous != null && previous.context != context) {
            throw new IllegalStateException(
                    "Foundry context handle is already registered: " + context.contextHandle());
        }
    }

    @Override
    public boolean initialize(long contextHandle, int level) {
        ContextState state = contexts.get(contextHandle);
        FoundryInitializationLevel initializationLevel =
                FoundryInitializationLevel.fromCode(level).orElse(null);
        if (state == null || initializationLevel == null || !state.context.isAlive()) {
            return false;
        }
        state.initializedLevels.add(initializationLevel);
        return true;
    }

    @Override
    public void deinitialize(long contextHandle, int level) {
        ContextState state = contexts.get(contextHandle);
        FoundryInitializationLevel initializationLevel =
                FoundryInitializationLevel.fromCode(level).orElse(null);
        if (state == null || initializationLevel == null) {
            return;
        }
        state.initializedLevels.remove(initializationLevel);
        if (initializationLevel == FoundryInitializationLevel.CORE
                && contexts.remove(contextHandle, state)) {
            state.context.close();
        }
    }

    @Override
    public long invoke(long contextHandle, long callbackHandle, long[] argumentHandles) {
        ContextState state = contexts.get(contextHandle);
        if (state == null) {
            return 0;
        }
        FoundryBindingContext context = state.context;
        try {
            return context.callbackRegistry()
                    .invokeBridge(
                            callbackHandle,
                            Objects.requireNonNull(argumentHandles, "argumentHandles").clone());
        } catch (CallbackRegistry.CallbackUnavailableException unavailable) {
            return 0;
        } catch (Throwable failure) {
            try {
                context.engine().reportCallbackException(contextHandle, callbackHandle, failure);
            } catch (Throwable ignored) {
                // Exception reporting is also contained at the bridge boundary.
            }
            return 0;
        }
    }

    @Override
    public void invalidate(long contextHandle) {
        ContextState state = contexts.remove(contextHandle);
        if (state != null) {
            state.context.close();
        }
    }

    private static final class ContextState {
        private final FoundryBindingContext context;
        private final Set<FoundryInitializationLevel> initializedLevels =
                ConcurrentHashMap.newKeySet();

        private ContextState(FoundryBindingContext context) {
            this.context = context;
        }
    }
}
