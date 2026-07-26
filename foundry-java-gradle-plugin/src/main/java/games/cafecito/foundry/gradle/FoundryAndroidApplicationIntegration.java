package games.cafecito.foundry.gradle;

import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import java.util.Locale;
import java.util.Set;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.TaskProvider;

/** Lazy public-Variant-API integration loaded only after the Android application plugin. */
final class FoundryAndroidApplicationIntegration {
    private static final Set<String> ANDROID_ABIS =
            Set.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64");

    private FoundryAndroidApplicationIntegration() {}

    static void configure(Project project, Configuration modules, FoundryJavaExtension extension) {
        extension.getRequestedAbis().convention(ANDROID_ABIS);
        ApplicationAndroidComponentsExtension androidComponents =
                project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
        androidComponents.onVariants(
                androidComponents.selector().all(),
                (Action<ApplicationVariant>)
                        variant -> {
                            selectRequestedBridgeAbis(extension, variant);
                            registerVariantTask(project, modules, extension, variant);
                        });
    }

    private static void selectRequestedBridgeAbis(
            FoundryJavaExtension extension, ApplicationVariant variant) {
        variant.getPackaging()
                .getJniLibs()
                .getExcludes()
                .addAll(
                        extension
                                .getRequestedAbis()
                                .map(
                                        requested ->
                                                ANDROID_ABIS.stream()
                                                        .filter(abi -> !requested.contains(abi))
                                                        .sorted()
                                                        .map(
                                                                abi ->
                                                                        "**/"
                                                                                + abi
                                                                                + "/"
                                                                                + "libfoundry_java.so")
                                                        .toList()));
    }

    private static void registerVariantTask(
            Project project,
            Configuration modules,
            FoundryJavaExtension extension,
            ApplicationVariant variant) {
        String variantName = capitalize(variant.getName());
        TaskProvider<RegistryIndexTask> registry =
                project.getTasks()
                        .register(
                                "generate" + variantName + "FoundryJavaRegistry",
                                RegistryIndexTask.class,
                                task -> {
                                    task.setGroup("build");
                                    task.setDescription(
                                            "Validates and generates the "
                                                    + variant.getName()
                                                    + " Foundry-Java registry.");
                                    task.getModuleArtifacts()
                                            .from(modules, variant.getRuntimeConfiguration());
                                    task.getPayloadArtifacts()
                                            .from(modules, variant.getRuntimeConfiguration());
                                    task.getRequestedAbis().set(extension.getRequestedAbis());
                                });
        variant.getSources()
                .getAssets()
                .addGeneratedSourceDirectory(registry, RegistryIndexTask::getAssetsOutputDirectory);
        variant.getSources()
                .getJava()
                .addGeneratedSourceDirectory(registry, RegistryIndexTask::getJavaOutputDirectory);
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}
