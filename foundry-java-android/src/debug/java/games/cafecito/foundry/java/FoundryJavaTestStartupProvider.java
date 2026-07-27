package games.cafecito.foundry.java;

import games.cafecito.foundry.runtime.FoundryRegistryBootstrap;

/** Debug-only provider that hands the immutable test bootstrap directly to startup priming. */
public final class FoundryJavaTestStartupProvider extends FoundryJavaStartupProvider {
    public FoundryJavaTestStartupProvider() {}

    @Override
    protected FoundryRegistryBootstrap bootstrap() {
        FoundryJavaStartupEvidence.recordProviderPrimed();
        return FoundryJavaTestRegistry.bootstrap();
    }
}
