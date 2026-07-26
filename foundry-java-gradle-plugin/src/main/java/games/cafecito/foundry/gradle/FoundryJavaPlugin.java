package games.cafecito.foundry.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.TaskProvider;

/** Entry point for consumer-side Foundry Java conventions. */
public final class FoundryJavaPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getExtensions().getExtraProperties().set("foundryJava", true);
        Configuration modules =
                project.getConfigurations()
                        .create(
                                "foundryJavaModules",
                                configuration -> {
                                    configuration.setCanBeConsumed(false);
                                    configuration.setCanBeResolved(true);
                                    configuration.setTransitive(true);
                                    configuration.setDescription(
                                            "Foundry-Java extension module descriptor artifacts.");
                                });
        TaskProvider<RegistryIndexTask> registry =
                project.getTasks()
                        .register(
                                "generateFoundryJavaRegistry",
                                RegistryIndexTask.class,
                                task -> {
                                    task.setGroup("build");
                                    task.setDescription(
                                            "Validates Foundry-Java modules and generates the registry bootstrap.");
                                    task.getModuleArtifacts().from(modules);
                                    task.getAssetsOutputDirectory()
                                            .set(
                                                    project.getLayout()
                                                            .getBuildDirectory()
                                                            .dir(
                                                                    "generated/foundryJava/"
                                                                            + "assets"));
                                    task.getJavaOutputDirectory()
                                            .set(
                                                    project.getLayout()
                                                            .getBuildDirectory()
                                                            .dir("generated/foundryJava/java"));
                                });
        project.getTasks()
                .matching(task -> task.getName().equals("preBuild"))
                .configureEach(task -> task.dependsOn(registry));
    }
}
