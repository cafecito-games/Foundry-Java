package games.cafecito.foundry.java;

import android.app.Application;
import android.os.Process;
import org.json.JSONObject;

/** Debug-only application proving provider priming completed before application startup. */
public final class FoundryJavaTestApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        FoundryJavaStartupEvidence.recordApplicationCreated();
        FoundryJavaStartupEvidence.recordProcess(Process.myPid());
        JSONObject preEntry = FoundryJavaTestHost.preEntryEvidence();
        FoundryJavaTestHost.requirePrimedPreEntry(preEntry);
        FoundryJavaStartupEvidence.recordApplicationPreEntry(preEntry);
    }
}
