package games.cafecito.foundry.gradle;

import org.gradle.api.provider.SetProperty;

/** Consumer configuration for deterministic Foundry-Java Android packaging. */
public abstract class FoundryJavaExtension {
    /** Android ABIs that the application will package and must find in the bridge AAR. */
    public abstract SetProperty<String> getRequestedAbis();
}
