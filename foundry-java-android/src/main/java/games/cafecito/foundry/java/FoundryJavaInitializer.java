package games.cafecito.foundry.java;

import games.cafecito.foundry.runtime.FoundryBridgeCallbacks;
import games.cafecito.foundry.runtime.FoundryRuntime;
import java.util.Objects;

/** Versioned Java entry point for the native FoundryExtension bridge. */
public final class FoundryJavaInitializer {
    static {
        System.loadLibrary("foundry_java");
    }

    private FoundryJavaInitializer() {}

    /**
     * Validates the generated API and runtime contracts, then installs the callback target.
     *
     * @param callbacks reentrant callback surface owned by the application
     * @return {@code true} when the native bridge accepted every contract value
     */
    public static boolean initialize(FoundryBridgeCallbacks callbacks) {
        return nativeBootstrapV1(
                FoundryJavaInitializer.class.getClassLoader(),
                Objects.requireNonNull(callbacks, "callbacks"),
                FoundryRuntime.API_SHA256,
                FoundryRuntime.GENERATOR_VERSION,
                FoundryRuntime.RUNTIME_CONTRACT_VERSION,
                FoundryRuntime.BRIDGE_CONTRACT_VERSION);
    }

    /** Creates a nonzero, generation-bound native context, or returns zero if unavailable. */
    public static long createContext() {
        return nativeCreateContextV1();
    }

    /** Invokes a callback on the current thread and returns zero when it cannot be delivered. */
    public static long invokeCallback(long context, long callback, long[] arguments) {
        return nativeInvokeCallbackV1(
                context, callback, Objects.requireNonNull(arguments, "arguments").clone());
    }

    /** Invokes a callback from a native-attached thread. */
    public static long invokeCallbackOnNativeThread(long context, long callback, long[] arguments) {
        return nativeInvokeCallbackOnThreadV1(
                context, callback, Objects.requireNonNull(arguments, "arguments").clone());
    }

    /** Closes a context after active callback leases drain. */
    public static boolean shutdownContext(long context) {
        return nativeShutdownContextV1(context);
    }

    /**
     * Closes all remaining contexts and releases native global references.
     *
     * <p>This is a process-lifetime teardown operation. The bridge cannot be initialized again
     * until the native library is unloaded and loaded by a new process.
     */
    public static void shutdownBridge() {
        nativeShutdownBridgeV1();
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
}
