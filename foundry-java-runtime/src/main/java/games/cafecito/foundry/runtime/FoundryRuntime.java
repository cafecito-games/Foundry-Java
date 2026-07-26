package games.cafecito.foundry.runtime;

import games.cafecito.foundry.generated.GeneratedApiProvenance;

/** Versioned, host-neutral entry point for the Java language runtime. */
public final class FoundryRuntime {
    public static final String RUNTIME_CONTRACT_VERSION = "1";
    public static final String API_SHA256 = GeneratedApiProvenance.API_SHA256;
    public static final String COMPATIBILITY_MANIFEST_SHA256 =
            GeneratedApiProvenance.COMPATIBILITY_MANIFEST_SHA256;
    public static final String GENERATOR_VERSION = GeneratedApiProvenance.GENERATOR_VERSION;
    public static final String BRIDGE_CONTRACT_VERSION =
            GeneratedApiProvenance.BRIDGE_CONTRACT_VERSION;

    private FoundryRuntime() {}
}
