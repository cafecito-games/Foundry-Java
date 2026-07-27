package games.cafecito.foundry.java;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import org.json.JSONObject;

/** Non-exported debug activity used to prove startup ordering through a real launch. */
public final class FoundryJavaTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FoundryJavaStartupEvidence.recordActivityCreated();
        FoundryJavaStartupEvidence.recordProcess(Process.myPid());
        JSONObject preEntry = FoundryJavaTestHost.preEntryEvidence();
        FoundryJavaTestHost.requirePrimedPreEntry(preEntry);
        FoundryJavaStartupEvidence.recordActivityPreEntry(preEntry);
    }
}
