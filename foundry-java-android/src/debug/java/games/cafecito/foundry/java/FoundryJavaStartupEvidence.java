package games.cafecito.foundry.java;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Process-local evidence captured by the debug production-startup acceptance fixture. */
public final class FoundryJavaStartupEvidence {
    public static final String FILE_NAME = "foundry-java-production-startup-evidence.json";

    private static final Object LOCK = new Object();
    private static final ArrayList<String> EVENTS = new ArrayList<>();
    private static CountDownLatch activityCreated = new CountDownLatch(1);
    private static int providerRegistrationCount;
    private static int applicationOnCreateCount;
    private static int activityOnCreateCount;
    private static int callbackDispatchCount;
    private static int exceptionDispatchCount;
    private static int invalidationCount;
    private static int processId;
    private static long callbackResult;
    private static String callbackThread = "";
    private static JSONObject applicationPreEntry;
    private static JSONObject activityPreEntry;

    private FoundryJavaStartupEvidence() {}

    public static void recordProviderPrimed() {
        synchronized (LOCK) {
            providerRegistrationCount++;
            EVENTS.add("provider_primed");
        }
    }

    public static void recordApplicationCreated() {
        synchronized (LOCK) {
            applicationOnCreateCount++;
            EVENTS.add("application_created");
        }
    }

    public static void recordActivityCreated() {
        synchronized (LOCK) {
            activityOnCreateCount++;
            EVENTS.add("activity_created");
        }
    }

    public static void recordProcess(int pid) {
        if (pid <= 0) {
            throw new IllegalArgumentException("Fixture PID must be positive.");
        }
        synchronized (LOCK) {
            if (processId != 0 && processId != pid) {
                throw new IllegalStateException(
                        "Fixture process changed from " + processId + " to " + pid + ".");
            }
            processId = pid;
        }
    }

    public static void recordApplicationPreEntry(JSONObject evidence) {
        synchronized (LOCK) {
            applicationPreEntry = copy(evidence);
        }
    }

    public static void recordActivityPreEntry(JSONObject evidence) {
        synchronized (LOCK) {
            activityPreEntry = copy(evidence);
            activityCreated.countDown();
        }
    }

    public static void recordCallbackDispatch(long result) {
        synchronized (LOCK) {
            callbackDispatchCount++;
            callbackResult = result;
            callbackThread = Thread.currentThread().getName();
            EVENTS.add("callback_dispatched");
        }
    }

    public static void recordExceptionDispatch() {
        synchronized (LOCK) {
            exceptionDispatchCount++;
            EVENTS.add("exception_contained");
        }
    }

    public static void recordInvalidation() {
        synchronized (LOCK) {
            invalidationCount++;
            EVENTS.add("lease_invalidated");
        }
    }

    public static boolean awaitActivity(long timeout, TimeUnit unit) throws InterruptedException {
        return activityCreated.await(timeout, unit);
    }

    public static boolean providerBeforeApplication() {
        synchronized (LOCK) {
            return eventBefore("provider_primed", "application_created");
        }
    }

    public static boolean providerBeforeActivity() {
        synchronized (LOCK) {
            return eventBefore("provider_primed", "activity_created");
        }
    }

    public static List<String> eventsForTesting() {
        synchronized (LOCK) {
            return List.copyOf(EVENTS);
        }
    }

    public static void resetForTesting() {
        synchronized (LOCK) {
            EVENTS.clear();
            activityCreated = new CountDownLatch(1);
            providerRegistrationCount = 0;
            applicationOnCreateCount = 0;
            activityOnCreateCount = 0;
            callbackDispatchCount = 0;
            exceptionDispatchCount = 0;
            invalidationCount = 0;
            processId = 0;
            callbackResult = 0;
            callbackThread = "";
            applicationPreEntry = null;
            activityPreEntry = null;
        }
    }

    public static JSONObject buildReport(
            int runIndex,
            String targetPackage,
            String authority,
            int pidBeforeLifecycle,
            int pidAfterLifecycle,
            JSONObject lifecycle,
            Throwable failure)
            throws JSONException {
        synchronized (LOCK) {
            JSONObject preEntry =
                    applicationPreEntry != null ? applicationPreEntry : activityPreEntry;
            int contextCountDuringPriming =
                    preEntry == null ? -1 : preEntry.getInt("live_contexts");
            int registrationCountDuringPriming =
                    preEntry == null ? -1 : preEntry.getInt("registered_classes");
            JSONArray registrationOrder = lifecycle.getJSONArray("registration_order");
            JSONArray teardownOrder = lifecycle.getJSONArray("unregistration_order");

            JSONObject report = new JSONObject();
            report.put("schema_version", 1);
            report.put("run_index", runIndex);
            report.put("pid", processId);
            report.put("pid_before_lifecycle", pidBeforeLifecycle);
            report.put("pid_after_lifecycle", pidAfterLifecycle);
            report.put("target_package", targetPackage);
            report.put("authority", authority);
            report.put(
                    "fresh_process",
                    providerRegistrationCount == 1
                            && applicationOnCreateCount == 1
                            && activityOnCreateCount == 1);
            report.put("provider_before_application", providerBeforeApplication());
            report.put("provider_before_activity", providerBeforeActivity());
            report.put("context_count_during_priming", contextCountDuringPriming);
            report.put("registered_class_count_during_priming", registrationCountDuringPriming);
            report.put("core_context_nonzero", lifecycle.getLong("context_handle") != 0L);
            report.put("provider_registration_count", providerRegistrationCount);
            report.put("application_on_create_count", applicationOnCreateCount);
            report.put("activity_on_create_count", activityOnCreateCount);
            report.put("callback_dispatch_count", callbackDispatchCount);
            report.put("invalidation_count", invalidationCount);
            report.put("callback_result", lifecycle.getLong("callback_result"));
            report.put(
                    "callback_thread_attached", lifecycle.getBoolean("callback_thread_attached"));
            report.put("exception_contained", lifecycle.getBoolean("exception_contained"));
            report.put(
                    "stale_instance_callback_rejected",
                    lifecycle.getBoolean("stale_instance_callback_rejected"));
            report.put("registration_order", registrationOrder);
            report.put("registration_counts", lifecycle.getJSONObject("registration_counts"));
            report.put("teardown_order", teardownOrder);
            report.put("unregistration_counts", lifecycle.getJSONObject("unregistration_counts"));
            report.put("events", new JSONArray(EVENTS));
            report.put("result", failure == null ? "pass" : "fail");
            report.put("failure", failure == null ? JSONObject.NULL : failureDescription(failure));

            report.put(
                    "descriptor_evaluation_count", FoundryJavaTestRegistry.descriptorEvaluations());
            report.put("callback_result_observed_in_java", callbackResult);
            report.put("callback_thread", callbackThread);
            report.put("exception_dispatch_count", exceptionDispatchCount);
            report.put(
                    "exception_default_is_nil", lifecycle.getBoolean("exception_default_is_nil"));
            report.put("initialize_attempts", lifecycle.getJSONArray("initialize_attempts"));
            report.put("deinitialize_attempts", lifecycle.getJSONArray("deinitialize_attempts"));
            report.put(
                    "live_instances_after_teardown",
                    lifecycle.getInt("live_instances_after_teardown"));
            report.put(
                    "live_handles_after_teardown", lifecycle.getInt("live_handles_after_teardown"));
            report.put(
                    "entry_active_after_teardown",
                    lifecycle.getBoolean("entry_active_after_teardown"));
            report.put("native_lifecycle", copy(lifecycle));
            return report;
        }
    }

    public static File writeAtomically(Context context, JSONObject report) throws IOException {
        File output = new File(context.getFilesDir(), FILE_NAME);
        AtomicFile atomicFile = new AtomicFile(output);
        FileOutputStream stream = null;
        try {
            stream = atomicFile.startWrite();
            stream.write((report.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            atomicFile.finishWrite(stream);
            return output;
        } catch (IOException | RuntimeException failure) {
            if (stream != null) {
                atomicFile.failWrite(stream);
            }
            throw failure;
        }
    }

    private static boolean eventBefore(String first, String second) {
        int firstIndex = EVENTS.indexOf(first);
        int secondIndex = EVENTS.indexOf(second);
        return firstIndex >= 0 && secondIndex > firstIndex;
    }

    private static JSONObject copy(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException failure) {
            throw new IllegalArgumentException("Invalid fixture JSON evidence.", failure);
        }
    }

    private static String failureDescription(Throwable failure) {
        return failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
    }
}
