package games.cafecito.foundry.java;

import games.cafecito.foundry.runtime.FoundryBridgeCallbacks;
import games.cafecito.foundry.runtime.FoundryEngine;
import games.cafecito.foundry.runtime.FoundryRegistryBootstrap;
import games.cafecito.foundry.runtime.FoundryRegistryCoordinator;
import games.cafecito.foundry.runtime.FoundryRuntime;
import java.util.List;
import java.util.Objects;
import java.util.function.LongFunction;

/** Versioned Java entry point for the native FoundryExtension bridge. */
public final class FoundryJavaInitializer {
    private static final FoundryRegistryBootstrap EMPTY_BOOTSTRAP =
            new FoundryRegistryBootstrap(List.of());
    private static final String LOG_PREFIX = "FOUNDRY_JAVA_BOOTSTRAP ";
    private static final PrimingState PROCESS_PRIMING =
            new PrimingState(
                    FoundryJavaInitializer::ensureNativeLibraryLoaded,
                    FoundryJavaInitializer::bootstrapPrimedCallbacks,
                    FoundryJavaInitializer::createProductionEngine,
                    json -> System.out.println(LOG_PREFIX + json));

    private FoundryJavaInitializer() {}

    static void prime(ClassLoader loader, FoundryRegistryBootstrap bootstrap) {
        PROCESS_PRIMING.prime(loader, bootstrap);
    }

    /**
     * Validates the generated API and runtime contracts, then installs the callback target.
     *
     * @param callbacks reentrant callback surface owned by the application
     * @return {@code true} when the native bridge accepted every contract value
     */
    public static boolean initialize(FoundryBridgeCallbacks callbacks) {
        return initialize(EMPTY_BOOTSTRAP, callbacks);
    }

    /**
     * Validates a generated registry handoff and installs its diagnostic callback target.
     *
     * @param bootstrap immutable, provenance-validated generated registry handoff
     * @param callbacks reentrant callback surface owned by the application
     * @return {@code true} when the native bridge accepted every contract value
     */
    public static boolean initialize(
            FoundryRegistryBootstrap bootstrap, FoundryBridgeCallbacks callbacks) {
        FoundryRegistryBootstrap checkedBootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        DiagnosticSink diagnostics = json -> System.out.println(LOG_PREFIX + json);
        try {
            ensureNativeLibraryLoaded();
        } catch (LinkageError failure) {
            diagnostics.write(diagnosticJson(checkedBootstrap, -1, "native_library_load"));
            throw failure;
        }

        FoundryBridgeCallbacks checkedCallbacks =
                diagnosticCallbacks(
                        checkedBootstrap,
                        Objects.requireNonNull(callbacks, "callbacks"),
                        diagnostics);
        try {
            boolean initialized =
                    nativeBootstrapV1(
                            FoundryJavaInitializer.class.getClassLoader(),
                            checkedCallbacks,
                            FoundryRuntime.API_SHA256,
                            FoundryRuntime.GENERATOR_VERSION,
                            FoundryRuntime.RUNTIME_CONTRACT_VERSION,
                            FoundryRuntime.BRIDGE_CONTRACT_VERSION);
            diagnostics.write(
                    diagnosticJson(
                            checkedBootstrap, -1, initialized ? "none" : "native_bootstrap"));
            return initialized;
        } catch (RuntimeException | LinkageError failure) {
            diagnostics.write(diagnosticJson(checkedBootstrap, -1, "native_bootstrap_exception"));
            throw failure;
        }
    }

    /** Creates a nonzero, generation-bound native context, or returns zero if unavailable. */
    public static long createContext() {
        ensureNativeLibraryLoaded();
        return nativeCreateContextV1();
    }

    /** Invokes a callback on the current thread and returns zero when it cannot be delivered. */
    public static long invokeCallback(long context, long callback, long[] arguments) {
        ensureNativeLibraryLoaded();
        return nativeInvokeCallbackV1(
                context, callback, Objects.requireNonNull(arguments, "arguments").clone());
    }

    /** Invokes a callback from a native-attached thread. */
    public static long invokeCallbackOnNativeThread(long context, long callback, long[] arguments) {
        ensureNativeLibraryLoaded();
        return nativeInvokeCallbackOnThreadV1(
                context, callback, Objects.requireNonNull(arguments, "arguments").clone());
    }

    /** Closes a context after active callback leases drain. */
    public static boolean shutdownContext(long context) {
        ensureNativeLibraryLoaded();
        return nativeShutdownContextV1(context);
    }

    /**
     * Closes all remaining contexts and releases native global references.
     *
     * <p>This is a process-lifetime teardown operation. The bridge cannot be initialized again
     * until the native library is unloaded and loaded by a new process.
     */
    public static void shutdownBridge() {
        ensureNativeLibraryLoaded();
        nativeShutdownBridgeV1();
    }

    static FoundryBridgeCallbacks diagnosticCallbacks(
            FoundryRegistryBootstrap bootstrap,
            FoundryBridgeCallbacks callbacks,
            DiagnosticSink diagnostics) {
        return new DiagnosticCallbacks(
                Objects.requireNonNull(bootstrap, "bootstrap"),
                Objects.requireNonNull(callbacks, "callbacks"),
                Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    static String diagnosticJson(
            FoundryRegistryBootstrap bootstrap, int initializationLevel, String failurePhase) {
        FoundryRegistryBootstrap checkedBootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        StringBuilder modules = new StringBuilder();
        for (String module : checkedBootstrap.moduleNames()) {
            if (modules.length() != 0) {
                modules.append(',');
            }
            modules.append('"').append(jsonString(module)).append('"');
        }
        return "{\"api_sha256\":\""
                + jsonString(FoundryRuntime.API_SHA256)
                + "\",\"generator_version\":\""
                + jsonString(FoundryRuntime.GENERATOR_VERSION)
                + "\",\"runtime_contract_version\":\""
                + jsonString(FoundryRuntime.RUNTIME_CONTRACT_VERSION)
                + "\",\"bridge_contract_version\":\""
                + jsonString(FoundryRuntime.BRIDGE_CONTRACT_VERSION)
                + "\",\"registry_modules\":["
                + modules
                + "],\"initialization_level\":"
                + initializationLevel
                + ",\"failure_phase\":\""
                + jsonString(Objects.requireNonNull(failurePhase, "failurePhase"))
                + "\"}";
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(
                                String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static void ensureNativeLibraryLoaded() {
        if (!NativeLibrary.LOADED) {
            throw new IllegalStateException("Foundry Java native library did not load.");
        }
    }

    private static boolean bootstrapPrimedCallbacks(
            ClassLoader classLoader, FoundryBridgeCallbacks callbacks) {
        return nativeBootstrapV1(
                classLoader,
                callbacks,
                FoundryRuntime.API_SHA256,
                FoundryRuntime.GENERATOR_VERSION,
                FoundryRuntime.RUNTIME_CONTRACT_VERSION,
                FoundryRuntime.BRIDGE_CONTRACT_VERSION);
    }

    private static FoundryEngine createProductionEngine(long contextHandle) {
        return new FoundryNativeEngine(contextHandle);
    }

    static IllegalStateException providerFailure(String message, Throwable cause) {
        String qualified =
                "Foundry Java startup failed: failure_phase=provider_pre_entry; " + message;
        return cause == null
                ? new IllegalStateException(qualified)
                : new IllegalStateException(qualified, cause);
    }

    private static native boolean nativeBootstrapV1(
            ClassLoader classLoader,
            FoundryBridgeCallbacks callbacks,
            String apiSha256,
            String generatorVersion,
            String runtimeVersion,
            String bridgeVersion);

    private static native long nativeCreateContextV1();

    private static native long nativeInvokeCallbackV1(
            long context, long callback, long[] arguments);

    private static native long nativeInvokeCallbackOnThreadV1(
            long context, long callback, long[] arguments);

    private static native boolean nativeShutdownContextV1(long context);

    private static native void nativeShutdownBridgeV1();

    @FunctionalInterface
    interface DiagnosticSink {
        void write(String diagnostic);
    }

    @FunctionalInterface
    interface NativeLoader {
        void load();
    }

    @FunctionalInterface
    interface NativeBootstrap {
        boolean bootstrap(ClassLoader loader, FoundryBridgeCallbacks callbacks);
    }

    static final class PrimingState {
        private final Object lock = new Object();
        private final NativeLoader nativeLoader;
        private final NativeBootstrap nativeBootstrap;
        private final LongFunction<? extends FoundryEngine> engineFactory;
        private final DiagnosticSink diagnostics;
        private Phase phase = Phase.EMPTY;
        private ClassLoader classLoader;
        private FoundryRegistryBootstrap bootstrap;

        PrimingState(
                NativeLoader nativeLoader,
                NativeBootstrap nativeBootstrap,
                LongFunction<? extends FoundryEngine> engineFactory,
                DiagnosticSink diagnostics) {
            this.nativeLoader = Objects.requireNonNull(nativeLoader, "nativeLoader");
            this.nativeBootstrap = Objects.requireNonNull(nativeBootstrap, "nativeBootstrap");
            this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
            this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        }

        void prime(ClassLoader requestedLoader, FoundryRegistryBootstrap requestedBootstrap) {
            if (requestedLoader == null || requestedBootstrap == null) {
                throw providerFailure("The class loader and typed bootstrap are required.", null);
            }
            String rejection = null;
            synchronized (lock) {
                if (phase == Phase.PRIMED
                        && classLoader == requestedLoader
                        && bootstrap == requestedBootstrap) {
                    return;
                }
                if (phase != Phase.EMPTY) {
                    rejection =
                            "Foundry Java startup is already "
                                    + phase.name().toLowerCase(java.util.Locale.ROOT)
                                    + ".";
                } else {
                    phase = Phase.ACTIVE;
                }
            }
            if (rejection != null) {
                diagnostics.write(diagnosticJson(requestedBootstrap, -1, "provider_pre_entry"));
                throw providerFailure(rejection, null);
            }

            try {
                FoundryRegistryCoordinator coordinator =
                        new FoundryRegistryCoordinator(requestedBootstrap, engineFactory);
                nativeLoader.load();
                if (!nativeBootstrap.bootstrap(
                        requestedLoader,
                        diagnosticCallbacks(requestedBootstrap, coordinator, diagnostics))) {
                    throw new IllegalStateException("The native bridge rejected primed callbacks.");
                }
                synchronized (lock) {
                    classLoader = requestedLoader;
                    bootstrap = requestedBootstrap;
                    phase = Phase.PRIMED;
                }
            } catch (RuntimeException | LinkageError failure) {
                synchronized (lock) {
                    phase = Phase.STALE;
                }
                String diagnostic = diagnosticJson(requestedBootstrap, -1, "provider_pre_entry");
                diagnostics.write(diagnostic);
                throw providerFailure("Provider priming did not complete.", failure);
            } catch (Error failure) {
                synchronized (lock) {
                    phase = Phase.STALE;
                }
                throw failure;
            }
        }

        private enum Phase {
            EMPTY,
            ACTIVE,
            PRIMED,
            STALE
        }
    }

    private static final class NativeLibrary {
        private static final boolean LOADED = load();

        private static boolean load() {
            System.loadLibrary("foundry_java");
            return true;
        }
    }

    private static final class DiagnosticCallbacks implements FoundryBridgeCallbacks {
        private final FoundryRegistryBootstrap bootstrap;
        private final FoundryBridgeCallbacks callbacks;
        private final DiagnosticSink diagnostics;

        private DiagnosticCallbacks(
                FoundryRegistryBootstrap bootstrap,
                FoundryBridgeCallbacks callbacks,
                DiagnosticSink diagnostics) {
            this.bootstrap = bootstrap;
            this.callbacks = callbacks;
            this.diagnostics = diagnostics;
        }

        @Override
        public boolean initialize(long contextHandle, int level) {
            try {
                boolean initialized = callbacks.initialize(contextHandle, level);
                diagnostics.write(
                        diagnosticJson(
                                bootstrap,
                                level,
                                initialized ? "none" : "initialization_callback"));
                return initialized;
            } catch (RuntimeException | Error failure) {
                diagnostics.write(diagnosticJson(bootstrap, level, "initialization_exception"));
                throw failure;
            }
        }

        @Override
        public void deinitialize(long contextHandle, int level) {
            try {
                callbacks.deinitialize(contextHandle, level);
                diagnostics.write(diagnosticJson(bootstrap, level, "none"));
            } catch (RuntimeException | Error failure) {
                diagnostics.write(diagnosticJson(bootstrap, level, "deinitialization_exception"));
                throw failure;
            }
        }

        @Override
        public long invoke(long contextHandle, long callbackHandle, long[] argumentHandles) {
            try {
                return callbacks.invoke(contextHandle, callbackHandle, argumentHandles);
            } catch (RuntimeException | Error failure) {
                diagnostics.write(diagnosticJson(bootstrap, -1, "callback_exception"));
                throw failure;
            }
        }

        @Override
        public void invalidate(long contextHandle) {
            try {
                callbacks.invalidate(contextHandle);
            } catch (RuntimeException | Error failure) {
                diagnostics.write(diagnosticJson(bootstrap, -1, "invalidation_exception"));
                throw failure;
            }
        }

        @Override
        public boolean terminalCleanupComplete(long contextHandle) {
            try {
                return callbacks.terminalCleanupComplete(contextHandle);
            } catch (RuntimeException | Error failure) {
                diagnostics.write(
                        diagnosticJson(bootstrap, -1, "terminal_cleanup_query_exception"));
                throw failure;
            }
        }
    }
}
