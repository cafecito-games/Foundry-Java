package games.cafecito.foundry.gradle;

import com.android.build.api.artifact.SingleArtifact;
import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import java.util.Locale;
import java.util.Set;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.tasks.TaskProvider;

/** Lazy public-Variant-API integration loaded only after the Android application plugin. */
final class FoundryAndroidApplicationIntegration {
    private static final String DESUGAR_LIBRARY = "com.android.tools:desugar_jdk_libs:2.1.5";
    private static final Set<String> ANDROID_ABIS =
            Set.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64");

    private FoundryAndroidApplicationIntegration() {}

    static void configure(Project project, Configuration modules, FoundryJavaExtension extension) {
        extension.getRequestedAbis().convention(ANDROID_ABIS);
        ApplicationAndroidComponentsExtension androidComponents =
                project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
        androidComponents.finalizeDsl(
                (Action<ApplicationExtension>)
                        android ->
                                android.getCompileOptions().setCoreLibraryDesugaringEnabled(true));
        ExternalModuleDependency desugarLibrary =
                (ExternalModuleDependency) project.getDependencies().create(DESUGAR_LIBRARY);
        desugarLibrary.version(version -> version.strictly("2.1.5"));
        project.getDependencies().add("coreLibraryDesugaring", desugarLibrary);
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
                                    task.getStartupProviderClass()
                                            .set(
                                                    "games.cafecito.foundry.generated."
                                                            + "FoundryGeneratedStartupProvider");
                                    task.getStartupAuthority()
                                            .set(
                                                    variant.getApplicationId()
                                                            .map(
                                                                    id ->
                                                                            id
                                                                                    + ".foundry-java-startup"));
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
        TaskProvider<VerifyStartupManifestTask> verify =
                project.getTasks()
                        .register(
                                "verify" + variantName + "FoundryJavaStartupManifest",
                                VerifyStartupManifestTask.class,
                                task -> {
                                    task.getVariantName().set(variant.getName());
                                    task.getExpectedProviderClass()
                                            .set(
                                                    registry.flatMap(
                                                            RegistryIndexTask
                                                                    ::getStartupProviderClass));
                                    task.getExpectedAuthority()
                                            .set(
                                                    registry.flatMap(
                                                            RegistryIndexTask
                                                                    ::getStartupAuthority));
                                    task.getRegistryAssetsDirectory()
                                            .set(
                                                    registry.flatMap(
                                                            RegistryIndexTask
                                                                    ::getAssetsOutputDirectory));
                                    task.getVerificationOutputDirectory()
                                            .set(
                                                    project.getLayout()
                                                            .getBuildDirectory()
                                                            .dir(
                                                                    "generated/foundryJava/"
                                                                            + "verification/"
                                                                            + variant.getName()));
                                });
        variant.getArtifacts()
                .use(verify)
                .wiredWith(VerifyStartupManifestTask::getInputManifest)
                .toListenTo(SingleArtifact.MERGED_MANIFEST.INSTANCE);
        variant.getSources()
                .getAssets()
                .addGeneratedSourceDirectory(
                        verify, VerifyStartupManifestTask::getVerificationOutputDirectory);
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}
