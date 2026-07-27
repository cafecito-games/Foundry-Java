package games.cafecito.foundry.runtime;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;

/**
 * Owns one production registry generation from native CORE initialization through teardown.
 *
 * <p>Generated access objects are passed directly through typed descriptors. This coordinator
 * performs no class loading, scanning, or reflection.
 */
public final class FoundryRegistryCoordinator implements FoundryBridgeCallbacks {
    private final Object lifecycleLock = new Object();
    private final FoundryRegistrationPlan plan;
    private final LongFunction<? extends FoundryEngine> engineFactory;
    private final LongConsumer terminalObserver;
    private final FoundryRuntimeCallbacks callbacks = new FoundryRuntimeCallbacks();
    private final EnumMap<FoundryInitializationLevel, List<FoundryClassDescriptor>> registered =
            new EnumMap<>(FoundryInitializationLevel.class);
    private long contextHandle;
    private FoundryEngine engine;
    private FoundryBindingContext context;
    private boolean transitionInProgress;
    private boolean terminal;

    public FoundryRegistryCoordinator(
            FoundryRegistryBootstrap bootstrap,
            LongFunction<? extends FoundryEngine> engineFactory) {
        this(bootstrap, engineFactory, ignored -> {});
    }

    FoundryRegistryCoordinator(
            FoundryRegistryBootstrap bootstrap,
            LongFunction<? extends FoundryEngine> engineFactory,
            LongConsumer terminalObserver) {
        plan = FoundryRegistrationPlan.create(Objects.requireNonNull(bootstrap, "bootstrap"));
        this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
        this.terminalObserver = Objects.requireNonNull(terminalObserver, "terminalObserver");
    }

    @Override
    public boolean initialize(long requestedContextHandle, int levelCode) {
        FoundryInitializationLevel level =
                FoundryInitializationLevel.fromCode(levelCode).orElse(null);
        if (requestedContextHandle == 0 || level == null) {
            return false;
        }
        InitializeReservation reservation = reserveInitialize(requestedContextHandle, level);
        if (reservation.transition() == null) {
            return reservation.alreadyInitialized();
        }
        Transition transition = reservation.transition();

        List<FoundryClassDescriptor> completed = new ArrayList<>();
        FoundryEngine activeEngine = transition.engine();
        FoundryBindingContext activeContext = transition.context();
        try {
            if (activeEngine == null) {
                activeEngine =
                        Objects.requireNonNull(
                                engineFactory.apply(requestedContextHandle), "engineFactory result");
                activeContext = new FoundryBindingContext(requestedContextHandle, activeEngine);
                callbacks.register(activeContext);
            }
            for (FoundryClassDescriptor descriptor : plan.classes(level)) {
                activeEngine.registerExtensionClass(requestedContextHandle, descriptor);
                completed.add(descriptor);
            }
            if (!callbacks.initialize(requestedContextHandle, levelCode)) {
                throw new IllegalStateException(
                        "Foundry runtime callback rejected initialization level " + level + ".");
            }
            publishInitialize(activeEngine, activeContext, level, completed);
            return true;
        } catch (Throwable failure) {
            markTerminal();
            rollback(requestedContextHandle, activeEngine, completed);
            rollback(requestedContextHandle, activeEngine, transition.registered());
            terminate(requestedContextHandle, activeContext, true);
            return false;
        }
    }

    @Override
    public void deinitialize(long requestedContextHandle, int levelCode) {
        FoundryInitializationLevel level =
                FoundryInitializationLevel.fromCode(levelCode).orElse(null);
        if (level == null) {
            return;
        }
        Transition transition = reserveDeinitialize(requestedContextHandle, level);
        if (transition == null) {
            return;
        }
        if (transition.terminal()) {
            rollback(requestedContextHandle, transition.engine(), transition.registered());
            callbacks.deinitialize(requestedContextHandle, levelCode);
            finishTerminal();
            terminalObserver.accept(requestedContextHandle);
            return;
        }
        rollback(requestedContextHandle, transition.engine(), transition.descriptors());
        callbacks.deinitialize(requestedContextHandle, levelCode);
        finishDeinitialize();
    }

    @Override
    public long invoke(long requestedContextHandle, long callbackHandle, long[] argumentHandles) {
        synchronized (lifecycleLock) {
            if (terminal || contextHandle != requestedContextHandle) {
                return 0;
            }
        }
        return callbacks.invoke(requestedContextHandle, callbackHandle, argumentHandles);
    }

    @Override
    public void invalidate(long requestedContextHandle) {
        Transition transition = reserveTerminal(requestedContextHandle);
        if (transition == null) {
            return;
        }
        rollback(requestedContextHandle, transition.engine(), transition.registered());
        callbacks.invalidate(requestedContextHandle);
        finishTerminal();
        terminalObserver.accept(requestedContextHandle);
    }

    private InitializeReservation reserveInitialize(
            long requestedContextHandle, FoundryInitializationLevel level) {
        synchronized (lifecycleLock) {
            if (transitionInProgress || terminal) {
                return InitializeReservation.rejected();
            }
            if (contextHandle != 0 && contextHandle != requestedContextHandle) {
                return InitializeReservation.rejected();
            }
            if (registered.containsKey(level)) {
                return new InitializeReservation(null, true);
            }
            FoundryInitializationLevel expected =
                    FoundryInitializationLevel.values()[registered.size()];
            if (level != expected) {
                return InitializeReservation.rejected();
            }
            if (contextHandle == 0 && level != FoundryInitializationLevel.CORE) {
                return InitializeReservation.rejected();
            }
            transitionInProgress = true;
            contextHandle = requestedContextHandle;
            return new InitializeReservation(
                    new Transition(
                            engine,
                            context,
                            List.of(),
                            new MapSnapshot(registered),
                            false),
                    false);
        }
    }

    private Transition reserveDeinitialize(
            long requestedContextHandle, FoundryInitializationLevel level) {
        synchronized (lifecycleLock) {
            if (transitionInProgress || terminal || contextHandle != requestedContextHandle) {
                return null;
            }
            if (!registered.containsKey(level)) {
                return null;
            }
            transitionInProgress = true;
            if (level == FoundryInitializationLevel.CORE) {
                terminal = true;
                return new Transition(
                        engine,
                        context,
                        List.of(),
                        new MapSnapshot(registered),
                        true);
            }
            FoundryInitializationLevel highest =
                    FoundryInitializationLevel.values()[registered.size() - 1];
            if (level != highest) {
                transitionInProgress = false;
                return null;
            }
            List<FoundryClassDescriptor> descriptors = registered.remove(level);
            return new Transition(
                    engine, context, descriptors, MapSnapshot.empty(), false);
        }
    }

    private Transition reserveTerminal(long requestedContextHandle) {
        synchronized (lifecycleLock) {
            if (transitionInProgress || terminal || contextHandle != requestedContextHandle) {
                return null;
            }
            transitionInProgress = true;
            terminal = true;
            return new Transition(
                    engine, context, List.of(), new MapSnapshot(registered), true);
        }
    }

    private void publishInitialize(
            FoundryEngine activeEngine,
            FoundryBindingContext activeContext,
            FoundryInitializationLevel level,
            List<FoundryClassDescriptor> completed) {
        synchronized (lifecycleLock) {
            engine = activeEngine;
            context = activeContext;
            registered.put(level, List.copyOf(completed));
            transitionInProgress = false;
            lifecycleLock.notifyAll();
        }
    }

    private void finishDeinitialize() {
        synchronized (lifecycleLock) {
            transitionInProgress = false;
            lifecycleLock.notifyAll();
        }
    }

    private void finishTerminal() {
        synchronized (lifecycleLock) {
            engine = null;
            context = null;
            contextHandle = 0;
            registered.clear();
            transitionInProgress = false;
            lifecycleLock.notifyAll();
        }
    }

    private void markTerminal() {
        synchronized (lifecycleLock) {
            terminal = true;
        }
    }

    private void terminate(
            long requestedContextHandle, FoundryBindingContext activeContext, boolean invalidate) {
        if (invalidate) {
            callbacks.invalidate(requestedContextHandle);
        } else if (activeContext != null) {
            activeContext.close();
        }
        synchronized (lifecycleLock) {
            terminal = true;
            engine = null;
            context = null;
            contextHandle = 0;
            registered.clear();
            transitionInProgress = false;
            lifecycleLock.notifyAll();
        }
        terminalObserver.accept(requestedContextHandle);
    }

    private void rollback(
            long requestedContextHandle,
            FoundryEngine activeEngine,
            List<FoundryClassDescriptor> descriptors) {
        if (activeEngine == null) {
            return;
        }
        for (int index = descriptors.size() - 1; index >= 0; index--) {
            try {
                activeEngine.unregisterExtensionClass(
                        requestedContextHandle, descriptors.get(index).foundryName());
            } catch (Throwable ignored) {
                // Continue rollback so later resources are never stranded.
            }
        }
    }

    private void rollback(
            long requestedContextHandle,
            FoundryEngine activeEngine,
            MapSnapshot registeredSnapshot) {
        for (FoundryInitializationLevel level : reverseLevels()) {
            rollback(
                    requestedContextHandle,
                    activeEngine,
                    registeredSnapshot.getOrDefault(level, List.of()));
        }
    }

    private static List<FoundryInitializationLevel> reverseLevels() {
        return List.of(
                FoundryInitializationLevel.EDITOR,
                FoundryInitializationLevel.SCENE,
                FoundryInitializationLevel.SERVERS,
                FoundryInitializationLevel.CORE);
    }

    private record Transition(
            FoundryEngine engine,
            FoundryBindingContext context,
            List<FoundryClassDescriptor> descriptors,
            MapSnapshot registered,
            boolean terminal) {}

    private record InitializeReservation(
            Transition transition, boolean alreadyInitialized) {
        static InitializeReservation rejected() {
            return new InitializeReservation(null, false);
        }
    }

    private static final class MapSnapshot {
        private final EnumMap<FoundryInitializationLevel, List<FoundryClassDescriptor>> values;

        private MapSnapshot(
                EnumMap<FoundryInitializationLevel, List<FoundryClassDescriptor>> source) {
            values = copy(source);
        }

        static MapSnapshot empty() {
            return new MapSnapshot(new EnumMap<>(FoundryInitializationLevel.class));
        }

        List<FoundryClassDescriptor> getOrDefault(
                FoundryInitializationLevel level, List<FoundryClassDescriptor> fallback) {
            return values.getOrDefault(level, fallback);
        }

        private static EnumMap<FoundryInitializationLevel, List<FoundryClassDescriptor>> copy(
                EnumMap<FoundryInitializationLevel, List<FoundryClassDescriptor>> source) {
            EnumMap<FoundryInitializationLevel, List<FoundryClassDescriptor>> result =
                    new EnumMap<>(FoundryInitializationLevel.class);
            source.forEach((level, descriptors) -> result.put(level, List.copyOf(descriptors)));
            return result;
        }
    }
}
