package games.cafecito.foundry.android;

import android.content.Context;
import games.cafecito.foundry.api.FoundryExtension;
import games.cafecito.foundry.runtime.FoundryRuntime;
import java.util.Objects;

/** Android-only adapter that attaches an application extension through the public Java ABI. */
public final class FoundryAndroidHost {
    private final Context applicationContext;

    public FoundryAndroidHost(Context context) {
        applicationContext = Objects.requireNonNull(context, "context").getApplicationContext();
    }

    public Context applicationContext() {
        return applicationContext;
    }

    public void attach(FoundryExtension extension) {
        FoundryRuntime.attach(extension);
    }
}
