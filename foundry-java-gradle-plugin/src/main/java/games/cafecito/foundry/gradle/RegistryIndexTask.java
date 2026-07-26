package games.cafecito.foundry.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/** Generates the deterministic registry index and direct Java provider bootstrap. */
public abstract class RegistryIndexTask extends DefaultTask {
    static final String DESCRIPTOR_PREFIX = "META-INF/foundry-java/modules/";
    static final String DESCRIPTOR_SUFFIX = ".descriptor";

    @Classpath
    public abstract ConfigurableFileCollection getModuleArtifacts();

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

        List<FoundryDescriptor> modules = readModules();
        if (modules.isEmpty()) {
            return;
        }
        modules = DescriptorValidator.validateGraph(modules, List.of(), Set.of());

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
        List<Path> artifacts =
                getModuleArtifacts().getFiles().stream()
                        .map(file -> file.toPath().toAbsolutePath().normalize())
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
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
        }
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
