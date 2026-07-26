package games.cafecito.foundry.java;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import games.cafecito.foundry.runtime.FoundryBridgeCallbacks;
import java.util.concurrent.atomic.AtomicInteger;

/** Device-side acceptance runner for the versioned JNI bridge lifecycle. */
public final class FoundryJavaInstrumentation extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_OK;
        try {
            exerciseBridge();
            result.putString("stream", "Foundry Java JNI lifecycle: PASS\n");
        } catch (Throwable failure) {
            resultCode = Activity.RESULT_CANCELED;
            result.putString(
                    "stream",
                    "Foundry Java JNI lifecycle: FAIL: "
                            + failure.getClass().getName()
                            + ": "
                            + failure.getMessage()
                            + "\n");
        } finally {
            FoundryJavaInitializer.shutdownBridge();
        }
        finish(resultCode, result);
    }

    private static void exerciseBridge() {
        LifecycleCallbacks callbacks = new LifecycleCallbacks();
        require(FoundryJavaInitializer.initialize(callbacks), "native bootstrap was rejected");

        long context = FoundryJavaInitializer.createContext();
        require(context != 0, "native context was not created");
        callbacks.context = context;

        long reentrant =
                FoundryJavaInitializer.invokeCallback(
                        context, LifecycleCallbacks.REENTRANT, new long[] {11, 13});
        require(reentrant == 42, "reentrant callback returned " + reentrant);

        long attached =
                FoundryJavaInitializer.invokeCallbackOnNativeThread(
                        context, LifecycleCallbacks.NATIVE_THREAD, new long[] {7, 9});
        require(attached == 16, "native-thread callback returned " + attached);

        long contained =
                FoundryJavaInitializer.invokeCallback(
                        context, LifecycleCallbacks.THROWING, new long[0]);
        require(contained == 0, "throwing callback crossed JNI with " + contained);

        require(FoundryJavaInitializer.shutdownContext(context), "context shutdown failed");
        require(callbacks.invalidations.get() == 1, "context was not invalidated exactly once");
        require(
                FoundryJavaInitializer.invokeCallback(
                                context, LifecycleCallbacks.NATIVE_THREAD, new long[0])
                        == 0,
                "stale context accepted a callback");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class LifecycleCallbacks implements FoundryBridgeCallbacks {
        private static final long REENTRANT = 1;
        private static final long INNER = 2;
        private static final long NATIVE_THREAD = 3;
        private static final long THROWING = 4;

        private final AtomicInteger invalidations = new AtomicInteger();
        private volatile long context;

        @Override
        public boolean initialize(long contextHandle, int level) {
            return contextHandle == context && level >= 0;
        }

        @Override
        public void deinitialize(long contextHandle, int level) {}

        @Override
        public long invoke(long contextHandle, long callbackHandle, long[] arguments) {
            require(contextHandle == context, "callback received the wrong context");
            if (callbackHandle == REENTRANT) {
                return FoundryJavaInitializer.invokeCallback(contextHandle, INNER, arguments);
            }
            if (callbackHandle == INNER) {
                require(arguments.length == 2, "reentrant arguments were not preserved");
                return arguments[0] + arguments[1] + 18;
            }
            if (callbackHandle == NATIVE_THREAD) {
                require(arguments.length == 2, "native-thread arguments were not preserved");
                return arguments[0] + arguments[1];
            }
            if (callbackHandle == THROWING) {
                throw new IllegalStateException("contained instrumentation exception");
            }
            return 0;
        }

        @Override
        public void invalidate(long contextHandle) {
            require(contextHandle == context, "invalidation received the wrong context");
            invalidations.incrementAndGet();
        }
    }
}
