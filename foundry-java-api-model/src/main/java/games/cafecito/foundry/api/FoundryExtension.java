package games.cafecito.foundry.api;

import games.cafecito.foundry.annotations.PublicFoundryAbi;

/**
 * The only public native-facing extension ABI exposed by Foundry-Java.
 *
 * <p>Implementations are supplied by applications and are invoked by the Foundry host.
 */
@PublicFoundryAbi
public interface FoundryExtension {}
