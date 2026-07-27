package games.cafecito.foundry.gradle;

import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import com.android.build.api.dsl.ApplicationExtension;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.provider.Provider;
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
        ApplicationExtension androidDsl =
                project.getExtensions().getByType(ApplicationExtension.class);
        androidComponents.onVariants(
                androidComponents.selector().all(),
                (Action<ApplicationVariant>)
                        variant -> {
                            selectRequestedBridgeAbis(extension, variant);
                            setManifestPlaceholder(
                                    androidDsl,
                                    variant,
                                    "foundryJavaStartupProvider",
                                    project.provider(
                                            () ->
                                                    "games.cafecito.foundry.generated."
                                                            + "FoundryGeneratedStartupProvider"));
                            setManifestPlaceholder(
                                    androidDsl,
                                    variant,
                                    "foundryJavaStartupAuthority",
                                    variant.getApplicationId()
                                            .map(id -> id + ".foundry-java-startup"));
                            registerVariantTask(project, modules, extension, variant);
                        });
    }

    private static void setManifestPlaceholder(
            ApplicationExtension androidDsl,
            ApplicationVariant variant,
            String key,
            Provider<String> expected) {
        checkConfiguredPlaceholder(
                variant,
                key,
                expected.get(),
                "defaultConfig",
                androidDsl.getDefaultConfig().getManifestPlaceholders());
        if (variant.getBuildType() != null) {
            checkConfiguredPlaceholder(
                    variant,
                    key,
                    expected.get(),
                    "build type " + variant.getBuildType(),
                    androidDsl
                            .getBuildTypes()
                            .getByName(variant.getBuildType())
                            .getManifestPlaceholders());
        }
        for (kotlin.Pair<String, String> flavor : variant.getProductFlavors()) {
            checkConfiguredPlaceholder(
                    variant,
                    key,
                    expected.get(),
                    "product flavor " + flavor.getSecond(),
                    androidDsl
                            .getProductFlavors()
                            .getByName(flavor.getSecond())
                            .getManifestPlaceholders());
        }
        variant.getManifestPlaceholders().put(key, expected);
    }

    private static void checkConfiguredPlaceholder(
            ApplicationVariant variant,
            String key,
            String expected,
            String source,
            Map<String, Object> configured) {
        Object existing = configured.get(key);
        if (existing != null && !existing.toString().equals(expected)) {
            throw new GradleException(
                    "Foundry-Java variant "
                            + variant.getName()
                            + " requires manifest placeholder "
                            + key
                            + "="
                            + expected
                            + " but "
                            + source
                            + " already sets "
                            + existing
                            + "; this would create an incompatible startup provider or authority.");
        }
    }

    private static void selectRequestedBridgeAbis(
            FoundryJavaExtension extension, ApplicationVariant variant) {
        variant.getPackaging()
                .getResources()
                .getExcludes()
                .add(RegistryIndexTask.FIXED_CONFIGURATION);
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
        variant.getSources()
                .getManifests()
                .addGeneratedManifestFile(registry, RegistryIndexTask::getManifestOutputFile);
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}
