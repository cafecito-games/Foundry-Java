package games.cafecito.foundry.java;

import org.json.JSONException;
import org.json.JSONObject;

/** Debug-only Java facade for the versioned native Android acceptance-test host. */
public final class FoundryJavaTestHost {
    private FoundryJavaTestHost() {}

    public static JSONObject preEntryEvidence() {
        JSONObject evidence = parse("pre_entry", invokePreEntry());
        requireInt(evidence, "schema_version", 1);
        requireKey(evidence, "bridge_ready");
        requireKey(evidence, "entry_active");
        requireKey(evidence, "live_contexts");
        requireKey(evidence, "registered_classes");
        return evidence;
    }

    public static JSONObject runLifecycle(int runIndex) {
        if (runIndex <= 0) {
            throw new IllegalArgumentException("foundry_run_index must be positive.");
        }
        JSONObject evidence = parse("lifecycle", invokeLifecycle(runIndex));
        requireInt(evidence, "schema_version", 1);
        requireInt(evidence, "run_index", runIndex);
        replaceContextHandle(evidence, requireObservedCoreContextHandle());
        return evidence;
    }

    static int requireRunIndex(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("foundry_run_index is required.");
        }
        try {
            int parsed = Integer.parseInt(encoded);
            if (parsed <= 0) {
                throw new IllegalArgumentException("foundry_run_index must be positive.");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "foundry_run_index must be a positive integer.", failure);
        }
    }

    static long requireObservedCoreContextHandle() {
        long contextHandle = FoundryJavaStartupEvidence.observedCoreContextHandle();
        require(contextHandle > 0L, "CORE context handle was not observed.");
        return contextHandle;
    }

    static void replaceContextHandle(JSONObject evidence, long observedContextHandle) {
        require(observedContextHandle > 0L, "CORE context handle was not observed.");
        try {
            require(
                    evidence.getLong("context_handle") == 0L,
                    "Native context_handle was not the expected placeholder.");
            evidence.put("context_handle", observedContextHandle);
        } catch (JSONException failure) {
            throw new IllegalStateException("Native evidence omitted context_handle.", failure);
        }
    }

    public static void requirePrimedPreEntry(JSONObject evidence) {
        try {
            require(evidence.getBoolean("bridge_ready"), "bridge was not ready before entry");
            require(!evidence.getBoolean("entry_active"), "entry was active before lifecycle");
            require(evidence.getInt("live_contexts") == 0, "provider priming created a context");
            require(
                    evidence.getInt("registered_classes") == 0,
                    "provider priming registered extension classes");
        } catch (JSONException failure) {
            throw new IllegalStateException("Malformed pre-entry evidence.", failure);
        }
    }

    private static String invokePreEntry() {
        NativeLibrary.ensureLoaded();
        return nativePreEntryEvidenceV1();
    }

    private static String invokeLifecycle(int runIndex) {
        NativeLibrary.ensureLoaded();
        return nativeRunLifecycleV1(runIndex);
    }

    private static JSONObject parse(String phase, String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("Native " + phase + " evidence was empty.");
        }
        try {
            return new JSONObject(encoded);
        } catch (JSONException failure) {
            throw new IllegalStateException("Native " + phase + " evidence was not JSON.", failure);
        }
    }

    private static void requireInt(JSONObject evidence, String name, int expected) {
        try {
            require(
                    evidence.getInt(name) == expected,
                    name + " was " + evidence.getInt(name) + ", expected " + expected);
        } catch (JSONException failure) {
            throw new IllegalStateException("Native evidence omitted " + name + ".", failure);
        }
    }

    private static void requireKey(JSONObject evidence, String name) {
        require(evidence.has(name), "Native evidence omitted " + name + ".");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static native String nativePreEntryEvidenceV1();

    private static native String nativeRunLifecycleV1(int runIndex);

    private static final class NativeLibrary {
        private static final boolean LOADED = load();

        private NativeLibrary() {}

        private static void ensureLoaded() {
            if (!LOADED) {
                throw new AssertionError("unreachable");
            }
        }

        private static boolean load() {
            System.loadLibrary("foundry_java_test_host");
            return true;
        }
    }
}
