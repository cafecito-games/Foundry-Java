package games.cafecito.foundry.java;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import games.cafecito.foundry.runtime.FoundryRegistryBootstrap;
import java.util.Objects;

/**
 * Stable pre-Activity entry point for an application-owned generated startup provider.
 *
 * <p>The generated subclass supplies its registry bootstrap directly. Startup only primes the class
 * loader and native callback bridge; native CORE initialization owns engine/context creation.
 */
public abstract class FoundryJavaStartupProvider extends ContentProvider {
    private final Primer primer;

    protected FoundryJavaStartupProvider() {
        this(FoundryJavaInitializer::prime);
    }

    FoundryJavaStartupProvider(Primer primer) {
        this.primer = Objects.requireNonNull(primer, "primer");
    }

    /** Returns the application bootstrap through a direct generated call. */
    protected abstract FoundryRegistryBootstrap bootstrap();

    @Override
    public final boolean onCreate() {
        try {
            primer.prime(
                    applicationClassLoader(),
                    Objects.requireNonNull(bootstrap(), "generated bootstrap"));
            return true;
        } catch (RuntimeException | LinkageError failure) {
            if (failure.getMessage() != null
                    && failure.getMessage().contains("failure_phase=provider_pre_entry")) {
                throw failure;
            }
            throw FoundryJavaInitializer.providerFailure(
                    "The generated startup provider did not complete.", failure);
        }
    }

    ClassLoader applicationClassLoader() {
        Context context =
                Objects.requireNonNull(getContext(), "startup provider application context");
        Context application = context.getApplicationContext();
        return (application != null ? application : context).getClassLoader();
    }

    @Override
    public final Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public final String getType(Uri uri) {
        return null;
    }

    @Override
    public final Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public final int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public final int update(
            Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @FunctionalInterface
    interface Primer {
        void prime(ClassLoader loader, FoundryRegistryBootstrap bootstrap);
    }
}
