package games.cafecito.foundry.gradle;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/** Generates the deterministic registry index and direct Java provider bootstrap. */
public abstract class RegistryIndexTask extends DefaultTask {
    static final String DESCRIPTOR_PREFIX = "META-INF/foundry-java/modules/";
    static final String DESCRIPTOR_SUFFIX = ".descriptor";
    static final String FIXED_CONFIGURATION = "FoundryJava.foundryextension";
    private static final Set<String> SUPPORTED_ABIS =
            Set.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64");

    @Classpath
    public abstract ConfigurableFileCollection getModuleArtifacts();

    @Classpath
    public abstract ConfigurableFileCollection getPayloadArtifacts();

    @Input
    public abstract SetProperty<String> getRequestedAbis();

    @OutputDirectory
    public abstract DirectoryProperty getAssetsOutputDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getJavaOutputDirectory();

    @TaskAction
    public void generate() throws IOException {
        Path assets = getAssetsOutputDirectory().get().getAsFile().toPath();
        Path javaSources = getJavaOutputDirectory().get().getAsFile().toPath();
        replaceDirectory(assets);
        replaceDirectory(javaSources);

        Set<String> requestedAbis = Set.copyOf(getRequestedAbis().get());
        for (String abi : requestedAbis) {
            if (!SUPPORTED_ABIS.contains(abi)) {
                throw new GradleException(
                        "Unsupported Foundry-Java ABI "
                                + abi
                                + "; expected one of "
                                + SUPPORTED_ABIS.stream().sorted().toList()
                                + ".");
            }
        }
        List<FoundryDescriptor> modules = readModules();
        List<DescriptorValidator.AndroidPayload> payloads = readPayloads();
        modules = DescriptorValidator.validateGraph(modules, payloads, requestedAbis);
        getLogger()
                .lifecycle(
                        "Foundry-Java registry {}: modules={}, payloads={}, requested_abis={}, "
                                + "assets={}, java={}",
                        getPath(),
                        modules.stream().map(FoundryDescriptor::module).toList(),
                        payloads.stream()
                                .map(DescriptorValidator.AndroidPayload::artifact)
                                .toList(),
                        requestedAbis.stream().sorted().toList(),
                        assets,
                        javaSources);
        if (modules.isEmpty()) {
            return;
        }

        Path index = assets.resolve("foundry_java/registry-index-v2.txt");
        Files.createDirectories(index.getParent());
        Files.writeString(index, index(modules), StandardCharsets.UTF_8);

        Path bootstrap =
                javaSources.resolve(
                        "games/cafecito/foundry/generated/FoundryGeneratedBootstrap.java");
        Files.createDirectories(bootstrap.getParent());
        Files.writeString(bootstrap, bootstrap(modules), StandardCharsets.UTF_8);
    }

    private List<FoundryDescriptor> readModules() throws IOException {
        List<Path> artifacts = sortedArtifacts(getModuleArtifacts());
        List<FoundryDescriptor> modules = new ArrayList<>();
        for (Path artifact : artifacts) {
            if (Files.isDirectory(artifact)) {
                readDirectory(artifact, modules);
            } else if (Files.isRegularFile(artifact)) {
                readArchive(artifact, modules);
            } else {
                throw new IOException("Foundry module artifact does not exist: " + artifact);
            }
        }
        return modules;
    }

    private static void readDirectory(Path artifact, List<FoundryDescriptor> modules)
            throws IOException {
        Path descriptors = artifact.resolve(DESCRIPTOR_PREFIX);
        if (!Files.isDirectory(descriptors)) {
            return;
        }
        try (var paths = Files.walk(descriptors)) {
            for (Path descriptor :
                    paths.filter(Files::isRegularFile)
                            .filter(
                                    path ->
                                            path.getFileName()
                                                    .toString()
                                                    .endsWith(DESCRIPTOR_SUFFIX))
                            .sorted()
                            .toList()) {
                modules.add(
                        DescriptorValidator.parse(
                                artifact.toString(),
                                artifact.relativize(descriptor).toString().replace('\\', '/'),
                                Files.readString(descriptor, StandardCharsets.UTF_8)));
            }
        }
    }

    private static void readArchive(Path artifact, List<FoundryDescriptor> modules)
            throws IOException {
        try (ZipFile archive = new ZipFile(artifact.toFile())) {
            List<? extends ZipEntry> descriptors =
                    archive.stream()
                            .filter(entry -> !entry.isDirectory())
                            .filter(
                                    entry ->
                                            entry.getName().startsWith(DESCRIPTOR_PREFIX)
                                                    && entry.getName().endsWith(DESCRIPTOR_SUFFIX))
                            .sorted(Comparator.comparing(ZipEntry::getName))
                            .toList();
            for (ZipEntry descriptor : descriptors) {
                String contents =
                        new String(
                                archive.getInputStream(descriptor).readAllBytes(),
                                StandardCharsets.UTF_8);
                modules.add(
                        DescriptorValidator.parse(
                                artifact.toString(), descriptor.getName(), contents));
            }
            ZipEntry classesJar = archive.getEntry("classes.jar");
            if (classesJar != null) {
                readNestedDescriptors(
                        artifact, archive.getInputStream(classesJar).readAllBytes(), modules);
            }
        }
    }

    private static void readNestedDescriptors(
            Path artifact, byte[] archiveBytes, List<FoundryDescriptor> modules)
            throws IOException {
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (!entry.isDirectory()
                        && entry.getName().startsWith(DESCRIPTOR_PREFIX)
                        && entry.getName().endsWith(DESCRIPTOR_SUFFIX)) {
                    modules.add(
                            DescriptorValidator.parse(
                                    artifact.toString(),
                                    "classes.jar!" + entry.getName(),
                                    new String(archive.readAllBytes(), StandardCharsets.UTF_8)));
                }
            }
        }
    }

    private List<DescriptorValidator.AndroidPayload> readPayloads() throws IOException {
        List<DescriptorValidator.AndroidPayload> payloads = new ArrayList<>();
        for (Path artifact : sortedArtifacts(getPayloadArtifacts())) {
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            try (ZipFile archive = new ZipFile(artifact.toFile())) {
                boolean configuration = false;
                Set<String> bridgeAbis = new java.util.TreeSet<>();
                for (ZipEntry entry :
                        archive.stream().filter(item -> !item.isDirectory()).toList()) {
                    String name = entry.getName();
                    if (name.equals("libfoundry_android.so")
                            || name.endsWith("/libfoundry_android.so")) {
                        throw new GradleException(
                                artifact + ": forbidden host payload " + name + ".");
                    }
                    String bridgeAbi = bridgeAbi(name);
                    if (bridgeAbi != null) {
                        bridgeAbis.add(bridgeAbi);
                    }
                    if (name.equals(FIXED_CONFIGURATION)) {
                        configuration = true;
                    }
                }
                ZipEntry classesJar = archive.getEntry("classes.jar");
                if (classesJar != null
                        && nestedEntryExists(
                                archive.getInputStream(classesJar), FIXED_CONFIGURATION)) {
                    configuration = true;
                }
                if (configuration || !bridgeAbis.isEmpty()) {
                    payloads.add(
                            new DescriptorValidator.AndroidPayload(
                                    artifact.toString(),
                                    !bridgeAbis.isEmpty(),
                                    configuration,
                                    bridgeAbis));
                }
            }
        }
        return payloads;
    }

    private static boolean nestedEntryExists(InputStream input, String expected)
            throws IOException {
        try (ZipInputStream archive = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().equals(expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String bridgeAbi(String name) {
        String prefix = "jni/";
        String suffix = "/libfoundry_java.so";
        if (!name.startsWith(prefix) || !name.endsWith(suffix)) {
            return null;
        }
        String abi = name.substring(prefix.length(), name.length() - suffix.length());
        return abi.isBlank() || abi.contains("/") ? null : abi;
    }

    private static List<Path> sortedArtifacts(ConfigurableFileCollection artifacts) {
        return artifacts.getFiles().stream()
                .map(file -> file.toPath().toAbsolutePath().normalize())
                .sorted(Comparator.comparing(Path::toString))
                .toList();
    }

    private static String index(List<FoundryDescriptor> modules) {
        FoundryDescriptor contract = modules.get(0);
        StringBuilder index =
                new StringBuilder()
                        .append("format=2\n")
                        .append("api_sha256=")
                        .append(contract.apiSha256())
                        .append('\n')
                        .append("generator_version=")
                        .append(contract.generatorVersion())
                        .append('\n')
                        .append("runtime_contract_version=")
                        .append(contract.runtimeContractVersion())
                        .append('\n')
                        .append("bridge_contract_version=")
                        .append(contract.bridgeContractVersion())
                        .append('\n');
        for (FoundryDescriptor module : modules) {
            index.append("module=")
                    .append(module.module())
                    .append('|')
                    .append(module.registry())
                    .append('\n');
        }
        return index.toString();
    }

    private static String bootstrap(List<FoundryDescriptor> modules) {
        String providers =
                modules.stream()
                        .map(module -> "                    " + module.registry() + ".PROVIDER")
                        .reduce((left, right) -> left + ",\n" + right)
                        .orElse("");
        return """
                package games.cafecito.foundry.generated;

                /** Deterministic direct-provider bootstrap generated by the Foundry-Java plugin. */
                public final class FoundryGeneratedBootstrap {
                    private static final games.cafecito.foundry.runtime.FoundryRegistryBootstrap INSTANCE =
                            new games.cafecito.foundry.runtime.FoundryRegistryBootstrap(
                                    java.util.List.of(
                %s));

                    private FoundryGeneratedBootstrap() {}

                    public static games.cafecito.foundry.runtime.FoundryRegistryBootstrap bootstrap() {
                        return INSTANCE;
                    }
                }
                """
                .formatted(providers);
    }

    private static void replaceDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(directory);
    }
}
