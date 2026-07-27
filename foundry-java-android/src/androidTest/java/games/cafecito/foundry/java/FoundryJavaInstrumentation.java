package games.cafecito.foundry.java;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Device-side acceptance runner for the production Android startup and JNI lifecycle. */
public final class FoundryJavaInstrumentation extends Instrumentation {
    private static final String TARGET_PACKAGE = "games.cafecito.foundry.android.test";
    private static final String PROVIDER_AUTHORITY = TARGET_PACKAGE + ".foundry-java-startup";
    private static final List<String> NATIVE_EVENTS =
            List.of(
                    "foundry_extension_entry",
                    "core_initialize",
                    "scene_initialize",
                    "callback_dispatch",
                    "scene_deinitialize",
                    "core_deinitialize",
                    "context_invalidate");
    private static final List<String> REQUIRED_EVENTS =
            List.of(
                    "provider_on_create",
                    "application_on_create",
                    "activity_on_create",
                    "foundry_extension_entry",
                    "core_initialize",
                    "scene_initialize",
                    "callback_dispatch",
                    "scene_deinitialize",
                    "core_deinitialize",
                    "context_invalidate");
    private Bundle arguments;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : new Bundle(arguments);
        start();
    }

    @Override
    public void onStart() {
        super.onStart();
        runAcceptance(arguments);
    }

    private void runAcceptance(Bundle arguments) {
        int runIndex = 0;
        Context targetContext = getTargetContext();
        Bundle result = new Bundle();
        Activity activity = null;
        JSONObject lifecycle = failureLifecycle(runIndex);
        int pidBeforeLifecycle = Process.myPid();
        int pidAfterLifecycle = pidBeforeLifecycle;
        Throwable failure = null;
        File evidenceFile = null;
        try {
            runIndex = parseRunIndex(arguments);
            lifecycle = failureLifecycle(runIndex);
            require(
                    TARGET_PACKAGE.equals(targetContext.getPackageName()),
                    "unexpected target package " + targetContext.getPackageName());
            validatePreEntryJsonContract();
            require(
                    FoundryJavaStartupEvidence.providerBeforeApplication(),
                    "provider did not prime before Application.onCreate");

            Intent intent = new Intent(targetContext, FoundryJavaTestActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            require(activity != null, "test Activity did not launch");
            require(
                    FoundryJavaStartupEvidence.awaitActivity(10, TimeUnit.SECONDS),
                    "test Activity startup timed out");
            require(
                    FoundryJavaStartupEvidence.providerBeforeActivity(),
                    "provider did not prime before Activity.onCreate");
            validateFailureReportContract(runIndex);

            pidBeforeLifecycle = Process.myPid();
            lifecycle = FoundryJavaTestHost.runLifecycle(runIndex);
            pidAfterLifecycle = Process.myPid();
            validateLifecycle(lifecycle, runIndex);
            require(
                    pidBeforeLifecycle == pidAfterLifecycle,
                    "native lifecycle changed the process PID");

            JSONObject report =
                    FoundryJavaStartupEvidence.buildReport(
                            runIndex,
                            TARGET_PACKAGE,
                            PROVIDER_AUTHORITY,
                            pidBeforeLifecycle,
                            pidAfterLifecycle,
                            lifecycle,
                            null);
            validateReport(report);
            evidenceFile = FoundryJavaStartupEvidence.writeAtomically(targetContext, report);
        } catch (Throwable caught) {
            failure = caught;
            pidAfterLifecycle = Process.myPid();
            try {
                JSONObject report =
                        FoundryJavaStartupEvidence.buildReport(
                                runIndex,
                                TARGET_PACKAGE,
                                PROVIDER_AUTHORITY,
                                pidBeforeLifecycle,
                                pidAfterLifecycle,
                                lifecycle,
                                caught);
                evidenceFile = FoundryJavaStartupEvidence.writeAtomically(targetContext, report);
            } catch (Throwable evidenceFailure) {
                caught.addSuppressed(evidenceFailure);
            }
        } finally {
            if (activity != null) {
                Activity launchedActivity = activity;
                runOnMainSync(launchedActivity::finish);
            }
        }

        if (failure == null) {
            result.putString("stream", "Foundry Java production startup: PASS\n");
            result.putString("evidence_file", evidenceFile.getAbsolutePath());
            finish(Activity.RESULT_OK, result);
        } else {
            result.putString(
                    "stream",
                    "Foundry Java production startup: FAIL: "
                            + failure.getClass().getName()
                            + ": "
                            + failure.getMessage()
                            + "\n");
            if (evidenceFile != null) {
                result.putString("evidence_file", evidenceFile.getAbsolutePath());
            }
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static void validatePreEntryJsonContract() throws JSONException {
        JSONObject authoritative = new JSONObject();
        authoritative.put("schema_version", 1);
        authoritative.put("bridge_ready", true);
        authoritative.put("entry_active", false);
        authoritative.put("live_contexts", 0);
        authoritative.put("registered_classes", 0);
        FoundryJavaTestHost.requirePrimedPreEntry(authoritative);

        JSONObject wrongType = new JSONObject(authoritative.toString());
        wrongType.put("registered_classes", new JSONArray());
        boolean rejected = false;
        try {
            FoundryJavaTestHost.requirePrimedPreEntry(wrongType);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        require(rejected, "registered_classes array was accepted");
    }

    private static void validateFailureReportContract(int runIndex) throws JSONException {
        JSONObject partialLifecycle =
                new JSONObject().put("schema_version", 1).put("run_index", runIndex);
        int pid = Process.myPid();
        JSONObject report =
                FoundryJavaStartupEvidence.buildReport(
                        runIndex,
                        TARGET_PACKAGE,
                        PROVIDER_AUTHORITY,
                        pid,
                        pid,
                        partialLifecycle,
                        new IllegalStateException("partial lifecycle contract probe"));
        require("fail".equals(report.getString("result")), "partial lifecycle report was not fail");
        JSONObject rawLifecycle = report.getJSONObject("native_lifecycle");
        require(rawLifecycle.length() == 2, "partial lifecycle evidence was not preserved");
        require(rawLifecycle.getInt("schema_version") == 1, "partial lifecycle schema was lost");
        require(
                rawLifecycle.getInt("run_index") == runIndex,
                "partial lifecycle run index was lost");
    }

    private static int parseRunIndex(Bundle arguments) {
        String encoded = arguments == null ? null : arguments.getString("foundry_run_index");
        return FoundryJavaTestHost.requireRunIndex(encoded);
    }

    private static void validateLifecycle(JSONObject lifecycle, int runIndex) throws JSONException {
        require(lifecycle.getInt("schema_version") == 1, "unexpected lifecycle schema");
        require(lifecycle.getInt("run_index") == runIndex, "lifecycle run index mismatch");
        require(lifecycle.getBoolean("entry_accepted"), "native entry was rejected");
        require(lifecycle.getLong("context_handle") == 1L, "fresh process context was not 1");
        requireArray(
                lifecycle.getJSONArray("initialize_attempts"),
                List.of("CORE", "CORE", "SCENE", "SCENE"),
                "initialize attempts");
        requireArray(
                lifecycle.getJSONArray("registration_order"),
                List.of("FoundryJavaTestCore", "FoundryJavaTestScene"),
                "registration order");
        requireExactCounts(lifecycle.getJSONObject("registration_counts"), "registration counts");
        require(lifecycle.getLong("callback_result") == 42L, "method callback was not 42");
        require(
                lifecycle.getBoolean("callback_thread_attached"),
                "native callback thread was not attached");
        require(
                lifecycle.getBoolean("exception_contained"),
                "Java exception crossed the native boundary");
        require(
                lifecycle.getBoolean("exception_default_is_nil"),
                "contained exception did not return NIL");
        require(
                lifecycle.getBoolean("stale_instance_callback_rejected"),
                "stale instance callback was accepted");
        requireArray(
                lifecycle.getJSONArray("deinitialize_attempts"),
                List.of("SCENE", "SCENE", "CORE", "CORE"),
                "deinitialize attempts");
        requireArray(
                lifecycle.getJSONArray("unregistration_order"),
                List.of("FoundryJavaTestScene", "FoundryJavaTestCore"),
                "unregistration order");
        requireExactCounts(
                lifecycle.getJSONObject("unregistration_counts"), "unregistration counts");
        require(
                lifecycle.getInt("live_instances_after_teardown") == 0,
                "live Java instances remained after teardown");
        require(
                lifecycle.getInt("live_handles_after_teardown") == 0,
                "live Java handles remained after teardown");
        require(
                !lifecycle.getBoolean("entry_active_after_teardown"),
                "native entry remained active after teardown");
        requireArray(lifecycle.getJSONArray("events"), NATIVE_EVENTS, "native lifecycle events");
    }

    private static void validateReport(JSONObject report) throws JSONException {
        require(report.getInt("schema_version") == 1, "unexpected report schema");
        require(
                TARGET_PACKAGE.equals(report.getString("target_package")),
                "evidence target package mismatch");
        require(
                PROVIDER_AUTHORITY.equals(report.getString("authority")),
                "evidence provider authority mismatch");
        int pid = report.getInt("pid");
        require(pid > 0, "evidence PID was not positive");
        require(report.getInt("pid_before_lifecycle") == pid, "pre-lifecycle PID changed");
        require(report.getInt("pid_after_lifecycle") == pid, "post-lifecycle PID changed");
        require(report.getBoolean("fresh_process"), "test did not run in a fresh process");
        require(
                report.getBoolean("provider_before_application"),
                "provider ordering evidence was false for Application");
        require(
                report.getBoolean("provider_before_activity"),
                "provider ordering evidence was false for Activity");
        require(
                report.getInt("context_count_during_priming") == 0,
                "provider priming created a context");
        require(
                report.getInt("registered_class_count_during_priming") == 0,
                "provider priming registered a class");
        require(report.getBoolean("core_context_nonzero"), "CORE did not create a context");
        require(report.getInt("provider_registration_count") == 1, "provider count was not 1");
        require(
                report.getInt("application_on_create_count") == 1,
                "Application.onCreate count was not 1");
        require(
                report.getInt("activity_on_create_count") == 1,
                "Activity.onCreate count was not 1");
        require(report.getInt("callback_dispatch_count") == 1, "callback count was not 1");
        require(report.getInt("invalidation_count") == 1, "invalidation count was not 1");
        require(
                report.getLong("callback_result_observed_in_java") == 42L,
                "Java method callback result was not 42");
        require(
                report.getInt("exception_dispatch_count") == 1,
                "throwing callback count was not 1");
        require(
                report.getInt("descriptor_evaluation_count") == 1,
                "provider descriptor was not evaluated exactly once");
        require(report.isNull("failure"), "passing evidence contained a failure");
        require("pass".equals(report.getString("result")), "evidence result was not pass");
        requireArray(report.getJSONArray("events"), REQUIRED_EVENTS, "startup events");
    }

    private static void requireExactCounts(JSONObject counts, String label) throws JSONException {
        require(counts.length() == 2, label + " contained unexpected classes");
        require(counts.getInt("FoundryJavaTestCore") == 1, label + " CORE was not 1");
        require(counts.getInt("FoundryJavaTestScene") == 1, label + " SCENE was not 1");
    }

    private static void requireArray(JSONArray actual, List<String> expected, String label)
            throws JSONException {
        require(actual.length() == expected.size(), label + " length mismatch");
        for (int index = 0; index < expected.size(); index++) {
            require(expected.get(index).equals(actual.getString(index)), label + " mismatch");
        }
    }

    private static JSONObject failureLifecycle(int runIndex) {
        try {
            JSONObject counts = new JSONObject();
            counts.put("FoundryJavaTestCore", 0);
            counts.put("FoundryJavaTestScene", 0);
            JSONObject lifecycle = new JSONObject();
            lifecycle.put("schema_version", 1);
            lifecycle.put("run_index", runIndex);
            lifecycle.put("entry_accepted", false);
            lifecycle.put("context_handle", 0L);
            lifecycle.put("initialize_attempts", new JSONArray());
            lifecycle.put("registration_order", new JSONArray());
            lifecycle.put("registration_counts", counts);
            lifecycle.put("callback_result", 0L);
            lifecycle.put("callback_thread_attached", false);
            lifecycle.put("exception_contained", false);
            lifecycle.put("exception_default_is_nil", false);
            lifecycle.put("stale_instance_callback_rejected", false);
            lifecycle.put("deinitialize_attempts", new JSONArray());
            lifecycle.put("unregistration_order", new JSONArray());
            lifecycle.put("unregistration_counts", new JSONObject(counts.toString()));
            lifecycle.put("live_instances_after_teardown", -1);
            lifecycle.put("live_handles_after_teardown", -1);
            lifecycle.put("entry_active_after_teardown", false);
            lifecycle.put("events", new JSONArray());
            return lifecycle;
        } catch (JSONException failure) {
            throw new AssertionError("Could not construct fallback evidence.", failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
