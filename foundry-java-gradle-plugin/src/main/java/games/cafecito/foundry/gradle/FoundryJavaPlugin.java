package games.cafecito.foundry.gradle;

import java.util.Set;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.TaskProvider;

/** Entry point for consumer-side Foundry Java conventions. */
public final class FoundryJavaPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        FoundryJavaExtension extension =
                project.getExtensions().create("foundryJava", FoundryJavaExtension.class);
        extension.getRequestedAbis().convention(Set.of());
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
                                    task.getPayloadArtifacts().from(modules);
                                    task.getRequestedAbis().set(extension.getRequestedAbis());
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
                                    task.getManifestOutputFile()
                                            .set(
                                                    project.getLayout()
                                                            .getBuildDirectory()
                                                            .file(
                                                                    "generated/foundryJava/"
                                                                            + "manifest/"
                                                                            + "AndroidManifest.xml"));
                                });
        rejectUnsupportedAndroidPlugins(project);
        project.getPluginManager()
                .withPlugin(
                        "com.android.application",
                        ignored ->
                                FoundryAndroidApplicationIntegration.configure(
                                        project, modules, extension));
    }

    private static void rejectUnsupportedAndroidPlugins(Project project) {
        for (String plugin :
                Set.of("com.android.library", "com.android.dynamic-feature", "com.android.test")) {
            project.getPluginManager()
                    .withPlugin(
                            plugin,
                            ignored -> {
                                throw new GradleException(
                                        "The Foundry-Java plugin requires "
                                                + "com.android.application; found "
                                                + plugin
                                                + ".");
                            });
        }
    }
}
