package games.cafecito.foundry.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Entry point for consumer-side Foundry Java conventions. */
public final class FoundryJavaPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getExtensions().getExtraProperties().set("foundryJava", true);
    }
}
