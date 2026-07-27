package games.cafecito.foundry.runtime;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
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
    private final BiFunction<Long, FoundryEngine, FoundryBindingContext> contextFactory;
    private final LongConsumer terminalObserver;
    private final Runnable beforeCallbackAdmission;
    private final Runnable afterTerminalReservation;
    private final FoundryRuntimeCallbacks callbacks = new FoundryRuntimeCallbacks();
    private final EnumMap<FoundryInitializationLevel, List<FoundryClassDescriptor>> registered =
            new EnumMap<>(FoundryInitializationLevel.class);
    private long contextHandle;
    private FoundryEngine engine;
    private FoundryBindingContext context;
    private boolean transitionInProgress;
    private boolean terminal;
    private boolean terminalRequested;
    private Completion pendingCompletion = Completion.INVALIDATE;
    private TerminalCleanup pendingCleanup;

    public FoundryRegistryCoordinator(
            FoundryRegistryBootstrap bootstrap,
            LongFunction<? extends FoundryEngine> engineFactory) {
        this(bootstrap, engineFactory, ignored -> {});
    }

    FoundryRegistryCoordinator(
            FoundryRegistryBootstrap bootstrap,
            LongFunction<? extends FoundryEngine> engineFactory,
            LongConsumer terminalObserver) {
        this(
                bootstrap,
                engineFactory,
                (handle, activeEngine) -> new FoundryBindingContext(handle, activeEngine),
                terminalObserver);
    }

    FoundryRegistryCoordinator(
            FoundryRegistryBootstrap bootstrap,
            LongFunction<? extends FoundryEngine> engineFactory,
            BiFunction<Long, FoundryEngine, FoundryBindingContext> contextFactory,
            LongConsumer terminalObserver) {
        this(bootstrap, engineFactory, contextFactory, terminalObserver, () -> {}, () -> {});
    }

    FoundryRegistryCoordinator(
            FoundryRegistryBootstrap bootstrap,
            LongFunction<? extends FoundryEngine> engineFactory,
            BiFunction<Long, FoundryEngine, FoundryBindingContext> contextFactory,
            LongConsumer terminalObserver,
            Runnable beforeCallbackAdmission,
            Runnable afterTerminalReservation) {
        plan = FoundryRegistrationPlan.create(Objects.requireNonNull(bootstrap, "bootstrap"));
        this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
        this.terminalObserver = Objects.requireNonNull(terminalObserver, "terminalObserver");
        this.beforeCallbackAdmission =
                Objects.requireNonNull(beforeCallbackAdmission, "beforeCallbackAdmission");
        this.afterTerminalReservation =
                Objects.requireNonNull(afterTerminalReservation, "afterTerminalReservation");
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
        ActiveTransition transition = reservation.transition();

        List<FoundryClassDescriptor> completed = new ArrayList<>();
        FoundryEngine activeEngine = transition.engine();
        FoundryBindingContext activeContext = transition.context();
        try {
            if (activeEngine == null) {
                activeEngine =
                        Objects.requireNonNull(
                                engineFactory.apply(requestedContextHandle),
                                "engineFactory result");
                activeContext =
                        Objects.requireNonNull(
                                contextFactory.apply(requestedContextHandle, activeEngine),
                                "contextFactory result");
                activeContext.publishRegistrationCatalog(plan.orderedClasses());
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
            TerminalCleanup cleanup =
                    publishInitialize(activeEngine, activeContext, level, completed);
            if (cleanup != null) {
                performTerminalCleanup(requestedContextHandle, cleanup);
                return false;
            }
            return true;
        } catch (Throwable failure) {
            TerminalCleanup cleanup =
                    prepareFailedInitialization(
                            activeEngine, activeContext, level, completed, failure);
            performTerminalCleanup(requestedContextHandle, cleanup);
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
        if (level == FoundryInitializationLevel.CORE) {
            TerminalCleanup cleanup =
                    reserveTerminal(requestedContextHandle, Completion.CORE_DEINITIALIZE);
            if (cleanup != null) {
                afterTerminalReservation.run();
                performTerminalCleanup(requestedContextHandle, cleanup);
            }
            return;
        }
        DeinitializeTransition transition = reserveDeinitialize(requestedContextHandle, level);
        if (transition == null) {
            return;
        }
        UnregisterResult unregister =
                unregisterLevel(
                        requestedContextHandle, transition.engine(), transition.descriptors());
        if (unregister.failure() != null) {
            TerminalCleanup cleanup =
                    promoteDeinitializeFailure(
                            transition, unregister.remainingCleanupOrder(), unregister.failure());
            performTerminalCleanup(requestedContextHandle, cleanup);
            return;
        }
        try {
            callbacks.deinitialize(requestedContextHandle, levelCode);
        } catch (Throwable failure) {
            TerminalCleanup cleanup = promoteDeinitializeFailure(transition, List.of(), failure);
            performTerminalCleanup(requestedContextHandle, cleanup);
            return;
        }
        TerminalCleanup cleanup = finishDeinitialize();
        if (cleanup != null) {
            performTerminalCleanup(requestedContextHandle, cleanup);
        }
    }

    @Override
    public long invoke(long requestedContextHandle, long callbackHandle, long[] argumentHandles) {
        synchronized (lifecycleLock) {
            if (terminal || contextHandle != requestedContextHandle) {
                return 0;
            }
            // Lifecycle transitions are reserved, not callback barriers. CORE registration can
            // synchronously invoke a callback after callbacks.register(activeContext), and the
            // callback registry's admission/drain protocol remains the shutdown authority.
        }
        beforeCallbackAdmission.run();
        try {
            return callbacks.invoke(requestedContextHandle, callbackHandle, argumentHandles);
        } finally {
            retryPendingCleanup(requestedContextHandle);
        }
    }

    @Override
    public void invalidate(long requestedContextHandle) {
        TerminalCleanup cleanup = reserveTerminal(requestedContextHandle, Completion.INVALIDATE);
        if (cleanup != null) {
            afterTerminalReservation.run();
            performTerminalCleanup(requestedContextHandle, cleanup);
        }
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
            return new InitializeReservation(new ActiveTransition(engine, context), false);
        }
    }

    private DeinitializeTransition reserveDeinitialize(
            long requestedContextHandle, FoundryInitializationLevel level) {
        synchronized (lifecycleLock) {
            if (transitionInProgress || terminal || contextHandle != requestedContextHandle) {
                return null;
            }
            if (!registered.containsKey(level)) {
                return null;
            }
            FoundryInitializationLevel highest =
                    FoundryInitializationLevel.values()[registered.size() - 1];
            if (level != highest) {
                return null;
            }
            transitionInProgress = true;
            List<FoundryClassDescriptor> descriptors = registered.remove(level);
            return new DeinitializeTransition(engine, context, descriptors);
        }
    }

    private TerminalCleanup reserveTerminal(long requestedContextHandle, Completion completion) {
        synchronized (lifecycleLock) {
            if (contextHandle != requestedContextHandle) {
                return null;
            }
            if (transitionInProgress) {
                if (!terminal) {
                    terminal = true;
                    terminalRequested = true;
                    pendingCompletion = completion;
                    closeCallbackAdmission(context);
                }
                return null;
            }
            if (pendingCleanup != null) {
                transitionInProgress = true;
                TerminalCleanup cleanup = pendingCleanup;
                pendingCleanup = null;
                return cleanup;
            }
            if (terminal) {
                return null;
            }
            transitionInProgress = true;
            terminal = true;
            closeCallbackAdmission(context);
            return cleanupFromRegistered(completion, null);
        }
    }

    private TerminalCleanup publishInitialize(
            FoundryEngine activeEngine,
            FoundryBindingContext activeContext,
            FoundryInitializationLevel level,
            List<FoundryClassDescriptor> completed) {
        synchronized (lifecycleLock) {
            engine = activeEngine;
            context = activeContext;
            registered.put(level, List.copyOf(completed));
            if (terminalRequested) {
                terminalRequested = false;
                closeCallbackAdmission(activeContext);
                return cleanupFromRegistered(pendingCompletion, null);
            }
            transitionInProgress = false;
            lifecycleLock.notifyAll();
            return null;
        }
    }

    private TerminalCleanup prepareFailedInitialization(
            FoundryEngine activeEngine,
            FoundryBindingContext activeContext,
            FoundryInitializationLevel level,
            List<FoundryClassDescriptor> completed,
            Throwable failure) {
        synchronized (lifecycleLock) {
            engine = activeEngine;
            context = activeContext;
            registered.put(level, List.copyOf(completed));
            terminal = true;
            terminalRequested = false;
            closeCallbackAdmission(activeContext);
            return cleanupFromRegistered(Completion.INVALIDATE, failure);
        }
    }

    private TerminalCleanup finishDeinitialize() {
        synchronized (lifecycleLock) {
            if (terminalRequested) {
                terminalRequested = false;
                return cleanupFromRegistered(pendingCompletion, null);
            }
            transitionInProgress = false;
            lifecycleLock.notifyAll();
            return null;
        }
    }

    private TerminalCleanup promoteDeinitializeFailure(
            DeinitializeTransition transition,
            List<FoundryClassDescriptor> failedLevelCleanup,
            Throwable failure) {
        synchronized (lifecycleLock) {
            terminal = true;
            terminalRequested = false;
            closeCallbackAdmission(transition.context());
            List<FoundryClassDescriptor> cleanupOrder = new ArrayList<>(failedLevelCleanup);
            cleanupOrder.addAll(new MapSnapshot(registered).reverseOrder());
            return new TerminalCleanup(
                    transition.engine(),
                    transition.context(),
                    List.copyOf(cleanupOrder),
                    Completion.INVALIDATE,
                    failure,
                    false);
        }
    }

    private TerminalCleanup cleanupFromRegistered(
            Completion completion, Throwable failureEvidence) {
        return new TerminalCleanup(
                engine,
                context,
                new MapSnapshot(registered).reverseOrder(),
                completion,
                failureEvidence,
                false);
    }

    private void performTerminalCleanup(long requestedContextHandle, TerminalCleanup cleanup) {
        if (cleanup.context() != null && !cleanup.context().drainCallbacks()) {
            retainPendingCleanup(cleanup);
            return;
        }
        if (cleanup.failureEvidence() != null && !cleanup.failureReported()) {
            reportCleanupFailure(
                    requestedContextHandle, cleanup.engine(), cleanup.failureEvidence());
            cleanup =
                    new TerminalCleanup(
                            cleanup.engine(),
                            cleanup.context(),
                            cleanup.cleanupOrder(),
                            cleanup.completion(),
                            cleanup.failureEvidence(),
                            true);
        }
        for (int index = 0; index < cleanup.cleanupOrder().size(); index++) {
            FoundryClassDescriptor descriptor = cleanup.cleanupOrder().get(index);
            try {
                cleanup.engine()
                        .unregisterExtensionClass(requestedContextHandle, descriptor.foundryName());
            } catch (Throwable failure) {
                Throwable evidence = combineEvidence(cleanup.failureEvidence(), failure);
                reportCleanupFailure(requestedContextHandle, cleanup.engine(), failure);
                retainPendingCleanup(
                        new TerminalCleanup(
                                cleanup.engine(),
                                cleanup.context(),
                                List.copyOf(
                                        cleanup.cleanupOrder()
                                                .subList(index, cleanup.cleanupOrder().size())),
                                cleanup.completion(),
                                evidence,
                                true));
                return;
            }
        }
        try {
            if (cleanup.completion() == Completion.CORE_DEINITIALIZE) {
                callbacks.deinitialize(
                        requestedContextHandle, FoundryInitializationLevel.CORE.code());
            } else {
                callbacks.invalidate(requestedContextHandle);
            }
        } catch (Throwable failure) {
            Throwable evidence = combineEvidence(cleanup.failureEvidence(), failure);
            reportCleanupFailure(requestedContextHandle, cleanup.engine(), failure);
            retainPendingCleanup(
                    new TerminalCleanup(
                            cleanup.engine(),
                            cleanup.context(),
                            List.of(),
                            cleanup.completion(),
                            evidence,
                            true));
            return;
        }
        finishTerminal();
        try {
            terminalObserver.accept(requestedContextHandle);
        } catch (Throwable failure) {
            reportCleanupFailure(requestedContextHandle, cleanup.engine(), failure);
        }
    }

    private void retryPendingCleanup(long requestedContextHandle) {
        TerminalCleanup cleanup;
        synchronized (lifecycleLock) {
            if (transitionInProgress
                    || pendingCleanup == null
                    || contextHandle != requestedContextHandle) {
                return;
            }
            transitionInProgress = true;
            cleanup = pendingCleanup;
            pendingCleanup = null;
        }
        performTerminalCleanup(requestedContextHandle, cleanup);
    }

    private void retainPendingCleanup(TerminalCleanup cleanup) {
        synchronized (lifecycleLock) {
            terminal = true;
            terminalRequested = false;
            pendingCleanup = cleanup;
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
            pendingCleanup = null;
            terminalRequested = false;
            transitionInProgress = false;
            lifecycleLock.notifyAll();
        }
    }

    private UnregisterResult unregisterLevel(
            long requestedContextHandle,
            FoundryEngine activeEngine,
            List<FoundryClassDescriptor> descriptors) {
        if (activeEngine == null) {
            return new UnregisterResult(List.of(), null);
        }
        for (int index = descriptors.size() - 1; index >= 0; index--) {
            try {
                activeEngine.unregisterExtensionClass(
                        requestedContextHandle, descriptors.get(index).foundryName());
            } catch (Throwable failure) {
                List<FoundryClassDescriptor> remaining = new ArrayList<>(index + 1);
                for (int remainingIndex = index; remainingIndex >= 0; remainingIndex--) {
                    remaining.add(descriptors.get(remainingIndex));
                }
                return new UnregisterResult(List.copyOf(remaining), failure);
            }
        }
        return new UnregisterResult(List.of(), null);
    }

    private static Throwable combineEvidence(Throwable existing, Throwable failure) {
        if (existing == null) {
            return failure;
        }
        if (existing != failure) {
            existing.addSuppressed(failure);
        }
        return existing;
    }

    private static void closeCallbackAdmission(FoundryBindingContext activeContext) {
        if (activeContext != null) {
            activeContext.closeCallbackAdmission();
        }
    }

    private static void reportCleanupFailure(
            long requestedContextHandle, FoundryEngine activeEngine, Throwable failure) {
        if (activeEngine == null) {
            return;
        }
        try {
            activeEngine.reportCallbackException(requestedContextHandle, 0, failure);
        } catch (Throwable reportingFailure) {
            if (reportingFailure != failure) {
                failure.addSuppressed(reportingFailure);
            }
        }
    }

    private static List<FoundryInitializationLevel> reverseLevels() {
        return List.of(
                FoundryInitializationLevel.EDITOR,
                FoundryInitializationLevel.SCENE,
                FoundryInitializationLevel.SERVERS,
                FoundryInitializationLevel.CORE);
    }

    private enum Completion {
        INVALIDATE,
        CORE_DEINITIALIZE
    }

    private record ActiveTransition(FoundryEngine engine, FoundryBindingContext context) {}

    private record InitializeReservation(ActiveTransition transition, boolean alreadyInitialized) {
        static InitializeReservation rejected() {
            return new InitializeReservation(null, false);
        }
    }

    private record DeinitializeTransition(
            FoundryEngine engine,
            FoundryBindingContext context,
            List<FoundryClassDescriptor> descriptors) {}

    private record TerminalCleanup(
            FoundryEngine engine,
            FoundryBindingContext context,
            List<FoundryClassDescriptor> cleanupOrder,
            Completion completion,
            Throwable failureEvidence,
            boolean failureReported) {}

    private record UnregisterResult(
            List<FoundryClassDescriptor> remainingCleanupOrder, Throwable failure) {}

    private static final class MapSnapshot {
        private final EnumMap<FoundryInitializationLevel, List<FoundryClassDescriptor>> values;

        private MapSnapshot(
                EnumMap<FoundryInitializationLevel, List<FoundryClassDescriptor>> source) {
            values = copy(source);
        }

        List<FoundryClassDescriptor> reverseOrder() {
            List<FoundryClassDescriptor> result = new ArrayList<>();
            for (FoundryInitializationLevel level : reverseLevels()) {
                List<FoundryClassDescriptor> descriptors = values.getOrDefault(level, List.of());
                for (int index = descriptors.size() - 1; index >= 0; index--) {
                    result.add(descriptors.get(index));
                }
            }
            return List.copyOf(result);
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
