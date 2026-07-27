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
        List<PayloadScan> payloadScans = readPayloads();
        List<DescriptorValidator.AndroidPayload> payloads =
                payloadScans.stream().map(PayloadScan::validation).toList();
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

        for (PayloadScan payload : payloadScans) {
            if (payload.configurationBytes() != null) {
                Files.write(assets.resolve(FIXED_CONFIGURATION), payload.configurationBytes());
            }
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
                        parseDescriptor(
                                artifact,
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
                modules.add(parseDescriptor(artifact, descriptor.getName(), contents));
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
                            parseDescriptor(
                                    artifact,
                                    "classes.jar!" + entry.getName(),
                                    entry.getName(),
                                    new String(archive.readAllBytes(), StandardCharsets.UTF_8)));
                }
            }
        }
    }

    private List<PayloadScan> readPayloads() throws IOException {
        List<PayloadScan> payloads = new ArrayList<>();
        for (Path artifact : sortedArtifacts(getPayloadArtifacts())) {
            if (!Files.isRegularFile(artifact)) {
                continue;
            }
            try (ZipFile archive = new ZipFile(artifact.toFile())) {
                byte[] configuration = null;
                Set<String> bridgeAbis = new java.util.TreeSet<>();
                Set<String> forbiddenHostEntries = new java.util.TreeSet<>();
                for (ZipEntry entry :
                        archive.stream().filter(item -> !item.isDirectory()).toList()) {
                    String name = entry.getName();
                    if (name.equals("libfoundry_android.so")
                            || name.endsWith("/libfoundry_android.so")) {
                        forbiddenHostEntries.add(name);
                    }
                    String bridgeAbi = bridgeAbi(name);
                    if (bridgeAbi != null && !bridgeAbis.add(bridgeAbi)) {
                        throw new GradleException(
                                artifact + ": duplicate bridge abi=" + bridgeAbi + " at " + name);
                    }
                    if (name.equals(FIXED_CONFIGURATION)) {
                        if (configuration != null) {
                            throw new GradleException(
                                    artifact
                                            + ": duplicate "
                                            + FIXED_CONFIGURATION
                                            + " entries at AAR root.");
                        }
                        configuration = archive.getInputStream(entry).readAllBytes();
                    }
                }
                ZipEntry classesJar = archive.getEntry("classes.jar");
                if (classesJar != null) {
                    byte[] nestedConfiguration =
                            nestedEntryBytes(
                                    archive.getInputStream(classesJar), FIXED_CONFIGURATION);
                    if (configuration != null && nestedConfiguration != null) {
                        throw new GradleException(
                                artifact
                                        + ": duplicate "
                                        + FIXED_CONFIGURATION
                                        + " entries at AAR root and classes.jar.");
                    }
                    if (nestedConfiguration != null) {
                        configuration = nestedConfiguration;
                    }
                }
                boolean bindingClaimant = configuration != null || !bridgeAbis.isEmpty();
                if (bindingClaimant && !forbiddenHostEntries.isEmpty()) {
                    throw new GradleException(
                            artifact
                                    + ": forbidden host payload "
                                    + String.join(", ", forbiddenHostEntries)
                                    + ".");
                }
                if (bindingClaimant) {
                    payloads.add(
                            new PayloadScan(
                                    new DescriptorValidator.AndroidPayload(
                                            artifact.toString(),
                                            !bridgeAbis.isEmpty(),
                                            configuration != null,
                                            bridgeAbis),
                                    configuration));
                }
            }
        }
        return payloads;
    }

    private static FoundryDescriptor parseDescriptor(
            Path artifact, String descriptorPath, String contents) {
        return parseDescriptor(artifact, descriptorPath, descriptorPath, contents);
    }

    private static FoundryDescriptor parseDescriptor(
            Path artifact, String descriptorPath, String contractPath, String contents) {
        FoundryDescriptor descriptor =
                DescriptorValidator.parse(artifact.toString(), descriptorPath, contents);
        String expectedPath = DESCRIPTOR_PREFIX + descriptor.module() + DESCRIPTOR_SUFFIX;
        if (!contractPath.equals(expectedPath)) {
            throw new GradleException(
                    descriptor.identity()
                            + ": descriptor path must be "
                            + expectedPath
                            + "; found "
                            + contractPath);
        }
        return descriptor;
    }

    private static byte[] nestedEntryBytes(InputStream input, String expected) throws IOException {
        try (ZipInputStream archive = new ZipInputStream(input)) {
            byte[] contents = null;
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().equals(expected)) {
                    if (contents != null) {
                        throw new GradleException(
                                "classes.jar contains duplicate " + expected + " entries.");
                    }
                    contents = archive.readAllBytes();
                }
            }
            return contents;
        }
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

    private record PayloadScan(
            DescriptorValidator.AndroidPayload validation, byte[] configurationBytes) {
        private PayloadScan {
            configurationBytes = configurationBytes == null ? null : configurationBytes.clone();
        }

        @Override
        public byte[] configurationBytes() {
            return configurationBytes == null ? null : configurationBytes.clone();
        }
    }
}
