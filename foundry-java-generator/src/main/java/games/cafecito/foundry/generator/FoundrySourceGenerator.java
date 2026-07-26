package games.cafecito.foundry.generator;

import games.cafecito.foundry.api.FoundryExtension;
import games.cafecito.foundry.api.model.ApiInputException;
import games.cafecito.foundry.api.model.ApiInputs;
import games.cafecito.foundry.api.model.CompatibilityManifest;
import games.cafecito.foundry.api.model.FoundryApi;
import games.cafecito.foundry.api.model.FoundryApiParser;
import games.cafecito.foundry.api.model.JsonValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Produces deterministic Java metadata for the complete accepted FoundryExtension API model. */
public final class FoundrySourceGenerator {
    private static final String PACKAGE = "games.cafecito.foundry.generated";
    private static final String PACKAGE_PATH = "games/cafecito/foundry/generated/";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    public static String extensionTypeName() {
        return FoundryExtension.class.getName();
    }

    public GeneratedTree generate(
            FoundryApi api, Metadata metadata, CompatibilityManifest manifest) {
        metadata.validate();
        requireManifestMetadata("api_sha256", manifest.apiSha256(), metadata.apiSha256());
        requireManifestMetadata(
                "generator_version", manifest.generatorVersion(), metadata.generatorVersion());
        requireManifestMetadata(
                "bridge_contract_version",
                manifest.bridgeContractVersion(),
                metadata.bridgeContractVersion());
        List<Descriptor> descriptors = descriptors(api);
        Map<String, FoundryApi.Entity> covered = new TreeMap<>();
        for (Descriptor descriptor : descriptors) {
            for (FoundryApi.Entity entity : descriptor.entities()) {
                if (covered.put(entity.identity(), entity) != null) {
                    throw new ApiInputException(
                            "Generator covered source identity twice: " + entity.identity() + ".");
                }
            }
        }
        Set<String> parsedIdentities =
                api.entities().stream()
                        .map(FoundryApi.Entity::identity)
                        .collect(Collectors.toSet());
        if (!covered.keySet().equals(parsedIdentities)) {
            Set<String> missing =
                    parsedIdentities.stream()
                            .filter(identity -> !covered.containsKey(identity))
                            .collect(Collectors.toSet());
            Set<String> extra =
                    covered.keySet().stream()
                            .filter(identity -> !parsedIdentities.contains(identity))
                            .collect(Collectors.toSet());
            throw new ApiInputException(
                    "Generated coverage differs from parsed API: missing="
                            + missing
                            + ", extra="
                            + extra
                            + ".");
        }

        Set<String> manifestIdentities =
                manifest.entries().stream()
                        .map(CompatibilityManifest.Entry::sourceIdentity)
                        .collect(Collectors.toSet());
        if (!manifestIdentities.equals(covered.keySet())) {
            throw new ApiInputException(
                    "Checked compatibility classifications differ from generated coverage.");
        }
        String manifestSha256 = sha256(manifest.canonicalJson());

        Map<String, String> sources = new TreeMap<>();
        sources.put(
                PACKAGE_PATH + "GeneratedApiProvenance.java",
                provenanceSource(metadata, manifestSha256));
        sources.put(
                PACKAGE_PATH + "GeneratedRegistration.java",
                registrationSource(metadata, manifestSha256, descriptors, covered.size()));
        for (Descriptor descriptor : descriptors) {
            String path = PACKAGE_PATH + descriptor.className() + ".java";
            if (sources.put(path, descriptorSource(metadata, manifestSha256, descriptor)) != null) {
                throw new ApiInputException("Generated source path collision: " + path + ".");
            }
        }
        Map<String, String> descriptorCatalog = new TreeMap<>();
        descriptors.forEach(
                descriptor ->
                        descriptorCatalog.put(descriptor.rootIdentity(), descriptor.className()));
        return new GeneratedTree(sources, covered.keySet(), manifest, descriptorCatalog);
    }

    public static void main(String[] arguments) {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: FoundrySourceGenerator <api-directory> <source-output> "
                            + "<compatibility-manifest>");
        }
        Path apiDirectory = Path.of(arguments[0]);
        ApiInputs inputs = ApiInputs.load(apiDirectory);
        FoundryApi api = FoundryApiParser.parse(inputs);
        Metadata metadata =
                new Metadata(
                        inputs.extensionApiSha256(),
                        inputs.interfaceHeaderSha256(),
                        inputs.provenance().foundryCommit(),
                        inputs.provenance().foundryVersion(),
                        inputs.provenance().generatorVersion(),
                        inputs.provenance().bridgeContractVersion());
        CompatibilityManifest manifest = CompatibilityManifest.parse(api, inputs);
        GeneratedTree generated = new FoundrySourceGenerator().generate(api, metadata, manifest);
        generated.writeTo(Path.of(arguments[1]));
        Path manifestPath = Path.of(arguments[2]);
        try {
            Files.writeString(
                    manifestPath, generated.manifest().canonicalJson(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ApiInputException(
                    "Could not write compatibility manifest " + manifestPath + ".", exception);
        }
    }

    private static List<Descriptor> descriptors(FoundryApi api) {
        List<Descriptor> descriptors = new ArrayList<>();
        for (var category : api.categories().entrySet()) {
            for (FoundryApi.Entity root : category.getValue()) {
                List<FoundryApi.Entity> entities = new ArrayList<>();
                flatten(root, entities);
                descriptors.add(
                        new Descriptor(
                                descriptorClassName(root),
                                category.getKey(),
                                root.identity(),
                                List.copyOf(entities)));
            }
        }
        descriptors.sort(Comparator.comparing(Descriptor::rootIdentity));
        return List.copyOf(descriptors);
    }

    private static void flatten(FoundryApi.Entity entity, List<FoundryApi.Entity> target) {
        target.add(entity);
        entity.children().forEach(child -> flatten(child, target));
    }

    private static String descriptorClassName(FoundryApi.Entity root) {
        String[] identityParts = root.identity().split("/", 2);
        String category = pascalCase(identityParts[0]);
        String sourceName = identityParts.length == 1 ? "Root" : pascalCase(identityParts[1]);
        return "Generated" + category + sourceName + "_" + sha256(root.identity()).substring(0, 12);
    }

    private static String pascalCase(String source) {
        StringBuilder result = new StringBuilder();
        boolean capitalize = true;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (!Character.isJavaIdentifierPart(character)) {
                capitalize = true;
                continue;
            }
            if (result.isEmpty() && !Character.isJavaIdentifierStart(character)) {
                result.append('_');
            }
            result.append(capitalize ? Character.toUpperCase(character) : character);
            capitalize = false;
        }
        return result.isEmpty() ? "Entity" : result.toString();
    }

    private static String provenanceSource(Metadata metadata, String manifestSha256) {
        return generatedHeader(metadata, manifestSha256)
                + """
                        package %s;

                        public final class GeneratedApiProvenance {
                            public static final String FOUNDRY_COMMIT = "%s";
                            public static final String FOUNDRY_VERSION = "%s";
                            public static final String API_SHA256 =
                                    "%s";
                            public static final String INTERFACE_HEADER_SHA256 =
                                    "%s";
                            public static final String COMPATIBILITY_MANIFEST_SHA256 =
                                    "%s";
                            public static final String GENERATOR_VERSION = "%s";
                            public static final String BRIDGE_CONTRACT_VERSION = "%s";

                            private GeneratedApiProvenance() {}
                        }
                        """
                        .formatted(
                                PACKAGE,
                                metadata.foundryCommit(),
                                javaStringBody(metadata.foundryVersion()),
                                metadata.apiSha256(),
                                metadata.interfaceHeaderSha256(),
                                manifestSha256,
                                javaStringBody(metadata.generatorVersion()),
                                javaStringBody(metadata.bridgeContractVersion()));
    }

    private static String registrationSource(
            Metadata metadata,
            String manifestSha256,
            List<Descriptor> descriptors,
            int entityCount) {
        StringBuilder source = new StringBuilder(generatedHeader(metadata, manifestSha256));
        source.append(
                """
                        package %s;

                        /** Deterministic registration inventory consumed by later runtime work. */
                        public final class GeneratedRegistration {
                            public static final int DESCRIPTOR_COUNT = %d;
                            public static final int ENTITY_COUNT = %d;

                            public record Descriptor(String rootIdentity, Class<?> descriptorClass) {}

                            private static final java.util.List<Descriptor> DESCRIPTORS = createDescriptors();

                            public static java.util.List<Descriptor> descriptors() {
                                return DESCRIPTORS;
                            }

                            private static java.util.List<Descriptor> createDescriptors() {
                                var descriptors = new java.util.ArrayList<Descriptor>(DESCRIPTOR_COUNT);
                        """
                        .formatted(PACKAGE, descriptors.size(), entityCount));
        int chunkCount = (descriptors.size() + 99) / 100;
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            source.append("        descriptors.addAll(descriptorChunk")
                    .append(chunk)
                    .append("());\n");
        }
        source.append(
                """
                                return java.util.List.copyOf(descriptors);
                            }

                        """);
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            int start = chunk * 100;
            int end = Math.min(start + 100, descriptors.size());
            source.append("    private static java.util.List<Descriptor> descriptorChunk")
                    .append(chunk)
                    .append("() {\n")
                    .append("        return java.util.List.of(\n");
            for (int index = start; index < end; index++) {
                Descriptor descriptor = descriptors.get(index);
                source.append("                new Descriptor(\"")
                        .append(javaStringBody(descriptor.rootIdentity()))
                        .append("\", ")
                        .append(descriptor.className())
                        .append(".class)")
                        .append(index + 1 == end ? "\n" : ",\n");
            }
            source.append("        );\n    }\n\n");
        }
        source.append(
                """
                            private GeneratedRegistration() {}
                        }
                        """);
        return source.toString();
    }

    private static String descriptorSource(
            Metadata metadata, String manifestSha256, Descriptor descriptor) {
        StringBuilder source = new StringBuilder(generatedHeader(metadata, manifestSha256));
        source.append("package ").append(PACKAGE).append(";\n\n");
        source.append("/** Immutable generated metadata for one normalized API root. */\n");
        for (FoundryApi.Entity entity : descriptor.entities()) {
            source.append("// entity ")
                    .append(entity.identity())
                    .append(" edge-base64 ")
                    .append(
                            Base64.getEncoder()
                                    .encodeToString(entity.edge().getBytes(StandardCharsets.UTF_8)))
                    .append(" ordinal ")
                    .append(entity.ordinal())
                    .append(" source-base64 ")
                    .append(
                            Base64.getEncoder()
                                    .encodeToString(
                                            scalarSource(entity.source())
                                                    .getBytes(StandardCharsets.UTF_8)))
                    .append('\n');
        }
        source.append("public final class ")
                .append(descriptor.className())
                .append(" {\n")
                .append("    public static final String ROOT_IDENTITY = \"")
                .append(javaStringBody(descriptor.rootIdentity()))
                .append("\";\n")
                .append("    public static final String CATEGORY = \"")
                .append(javaStringBody(descriptor.category()))
                .append("\";\n")
                .append("    public static final int ENTITY_COUNT = ")
                .append(descriptor.entities().size())
                .append(";\n\n")
                .append("    private ")
                .append(descriptor.className())
                .append("() {}\n")
                .append("}\n");
        return source.toString();
    }

    private static String scalarSource(JsonValue.JsonObject object) {
        Map<String, JsonValue> scalars = new TreeMap<>();
        for (var field : object.values().entrySet()) {
            if (!(field.getValue() instanceof JsonValue.JsonArray)
                    && !(field.getValue() instanceof JsonValue.JsonObject)) {
                scalars.put(field.getKey(), field.getValue());
            }
        }
        return new JsonValue.JsonObject(scalars).canonicalJson();
    }

    private static String generatedHeader(Metadata metadata, String manifestSha256) {
        return "// Generated by Foundry-Java generator "
                + metadata.generatorVersion()
                + ".\n"
                + "// Foundry "
                + metadata.foundryVersion()
                + " commit "
                + metadata.foundryCommit()
                + ".\n"
                + "// Foundry API SHA-256: "
                + metadata.apiSha256()
                + "\n"
                + "// FoundryExtension header SHA-256: "
                + metadata.interfaceHeaderSha256()
                + "\n"
                + "// Compatibility manifest SHA-256: "
                + manifestSha256
                + "\n";
    }

    private static String javaStringBody(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java runtime provides SHA-256.", exception);
        }
    }

    public record Metadata(
            String apiSha256,
            String interfaceHeaderSha256,
            String foundryCommit,
            String foundryVersion,
            String generatorVersion,
            String bridgeContractVersion) {
        void validate() {
            if (!SHA256.matcher(apiSha256).matches()) {
                throw new ApiInputException("API SHA-256 must be 64 lowercase hexadecimal digits.");
            }
            if (!SHA256.matcher(interfaceHeaderSha256).matches()) {
                throw new ApiInputException(
                        "Interface header SHA-256 must be 64 lowercase hexadecimal digits.");
            }
            if (!COMMIT.matcher(foundryCommit).matches()) {
                throw new ApiInputException(
                        "Foundry commit must be 40 lowercase hexadecimal digits.");
            }
            for (String value : List.of(foundryVersion, generatorVersion, bridgeContractVersion)) {
                if (value == null
                        || value.isBlank()
                        || value.codePoints().anyMatch(Character::isISOControl)) {
                    throw new ApiInputException(
                            "Generation metadata values must not be blank or contain controls.");
                }
            }
        }
    }

    private static void requireManifestMetadata(
            String field, String manifestValue, String metadataValue) {
        if (!manifestValue.equals(metadataValue)) {
            throw new ApiInputException(
                    "Compatibility manifest "
                            + field
                            + " does not match verified generation metadata.");
        }
    }

    private record Descriptor(
            String className,
            String category,
            String rootIdentity,
            List<FoundryApi.Entity> entities) {}
}
