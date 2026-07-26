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
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
        List<PublicRoot> publicRoots = publicRoots(descriptors);

        Map<String, String> sources = new TreeMap<>();
        sources.put(
                PACKAGE_PATH + "GeneratedApiProvenance.java",
                provenanceSource(metadata, manifestSha256));
        sources.put(
                PACKAGE_PATH + "GeneratedRegistration.java",
                registrationSource(metadata, manifestSha256, descriptors, covered.size()));
        sources.put(
                PACKAGE_PATH + "GeneratedPublicApi.java",
                publicApiSource(metadata, manifestSha256, publicRoots));
        for (Descriptor descriptor : descriptors) {
            String path = PACKAGE_PATH + descriptor.className() + ".java";
            if (sources.put(path, descriptorSource(metadata, manifestSha256, descriptor)) != null) {
                throw new ApiInputException("Generated source path collision: " + path + ".");
            }
        }
        for (PublicRoot publicRoot : publicRoots) {
            String path = publicRoot.sourcePath();
            if (sources.put(path, publicRootSource(metadata, manifestSha256, publicRoot)) != null) {
                throw new ApiInputException(
                        "Generated public source path collision: " + path + ".");
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
        generated.writeReplacing(Path.of(arguments[1]));
        Path manifestPath = Path.of(arguments[2]);
        String manifestJson = generated.manifest().canonicalJson();
        try {
            if (Files.isRegularFile(manifestPath)
                    && Files.readString(manifestPath, StandardCharsets.UTF_8)
                            .equals(manifestJson)) {
                return;
            }
            Path parent = manifestPath.toAbsolutePath().normalize().getParent();
            if (parent == null) {
                throw new IOException("Manifest output must have a parent.");
            }
            Files.createDirectories(parent);
            Path staging = Files.createTempFile(parent, ".compatibility-manifest-", ".json");
            try {
                Files.writeString(staging, manifestJson, StandardCharsets.UTF_8);
                try {
                    Files.move(
                            staging,
                            manifestPath,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                    Files.move(staging, manifestPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(staging);
            }
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

    private static List<PublicRoot> publicRoots(List<Descriptor> descriptors) {
        Map<String, PublicRoot> rootsByIdentity = new LinkedHashMap<>();
        for (Descriptor descriptor : descriptors) {
            String packageSuffix = publicPackageSuffix(descriptor.category());
            String className =
                    "Api"
                            + pascalCase(descriptor.rootIdentity().split("/", 2)[1])
                            + "_"
                            + sha256(descriptor.rootIdentity()).substring(0, 12);
            PublicRoot root =
                    new PublicRoot(
                            descriptor,
                            PACKAGE + "." + packageSuffix,
                            PACKAGE_PATH
                                    + packageSuffix.replace('.', '/')
                                    + "/"
                                    + className
                                    + ".java",
                            className,
                            "");
            rootsByIdentity.put(descriptor.rootIdentity(), root);
        }

        List<PublicRoot> roots = new ArrayList<>();
        for (PublicRoot root : rootsByIdentity.values()) {
            String parentClassName = "";
            if (root.descriptor().category().equals("classes")) {
                JsonValue inherits =
                        root.descriptor().entities().get(0).source().optional("inherits");
                if (inherits != null) {
                    String parentName =
                            inherits.requireString(root.descriptor().rootIdentity() + ".inherits");
                    if (!parentName.isBlank()) {
                        PublicRoot parent = rootsByIdentity.get("classes/" + parentName);
                        if (parent == null) {
                            throw new ApiInputException(
                                    "Generated class parent is absent: "
                                            + root.descriptor().rootIdentity()
                                            + " inherits "
                                            + parentName
                                            + ".");
                        }
                        parentClassName = parent.className();
                    }
                }
            }
            roots.add(root.withParentClassName(parentClassName));
        }
        roots.sort(Comparator.comparing(root -> root.descriptor().rootIdentity()));
        return List.copyOf(roots);
    }

    private static String publicPackageSuffix(String category) {
        return switch (category) {
            case "classes" -> "classes";
            case "builtin_classes" -> "builtins";
            case "singletons" -> "singletons";
            case "utility_functions" -> "utilities";
            case "global_constants", "global_enums" -> "globals";
            case "builtin_class_sizes", "builtin_class_member_offsets" -> "layout";
            case "native_structures" -> "structures";
            default ->
                    throw new ApiInputException(
                            "No generated public package for API category " + category + ".");
        };
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

    private static String publicApiSource(
            Metadata metadata, String manifestSha256, List<PublicRoot> roots) {
        StringBuilder source = new StringBuilder(generatedHeader(metadata, manifestSha256));
        source.append(
                """
                        package %s;

                        /** Exhaustive deterministic inventory of generated public API roots. */
                        public final class GeneratedPublicApi {
                            public static final int ROOT_COUNT = %d;

                            public record Root(String sourceIdentity, Class<?> publicClass) {}

                            private static final java.util.List<Root> ROOTS = createRoots();

                            public static java.util.List<Root> roots() {
                                return ROOTS;
                            }

                            private static java.util.List<Root> createRoots() {
                                var roots = new java.util.ArrayList<Root>(ROOT_COUNT);
                        """
                        .formatted(PACKAGE, roots.size()));
        int chunkCount = (roots.size() + 99) / 100;
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            source.append("        roots.addAll(rootChunk").append(chunk).append("());\n");
        }
        source.append(
                """
                                return java.util.List.copyOf(roots);
                            }

                        """);
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            int start = chunk * 100;
            int end = Math.min(start + 100, roots.size());
            source.append("    private static java.util.List<Root> rootChunk")
                    .append(chunk)
                    .append("() {\n")
                    .append("        return java.util.List.of(\n");
            for (int index = start; index < end; index++) {
                PublicRoot root = roots.get(index);
                source.append("                new Root(decode(\"")
                        .append(base64(root.descriptor().rootIdentity()))
                        .append("\"), ")
                        .append(root.packageName())
                        .append('.')
                        .append(root.className())
                        .append(".class)")
                        .append(index + 1 == end ? "\n" : ",\n");
            }
            source.append("        );\n    }\n\n");
        }
        source.append(
                """
                            private static String decode(String encoded) {
                                return new String(
                                        java.util.Base64.getDecoder().decode(encoded),
                                        java.nio.charset.StandardCharsets.UTF_8);
                            }

                            private GeneratedPublicApi() {}
                        }
                        """);
        return source.toString();
    }

    private static String publicRootSource(
            Metadata metadata, String manifestSha256, PublicRoot root) {
        return switch (root.descriptor().category()) {
            case "classes" -> classRootSource(metadata, manifestSha256, root);
            case "singletons" -> singletonRootSource(metadata, manifestSha256, root);
            case "builtin_classes" -> builtinRootSource(metadata, manifestSha256, root);
            default -> staticRootSource(metadata, manifestSha256, root);
        };
    }

    private static String classRootSource(
            Metadata metadata, String manifestSha256, PublicRoot root) {
        StringBuilder source = publicRootHeader(metadata, manifestSha256, root);
        source.append("public class ").append(root.className());
        if (!root.parentClassName().isEmpty()) {
            source.append(" extends ").append(root.parentClassName());
        } else {
            source.append(" extends games.cafecito.foundry.runtime.FoundryObject");
        }
        source.append(" {\n");
        source.append("    protected ")
                .append(root.className())
                .append(
                        """
                                (
                                        games.cafecito.foundry.runtime.FoundryBindingContext context,
                                        games.cafecito.foundry.runtime.ObjectLease lease) {
                                super(context, lease);
                            }

                            public static %s bind(
                                    games.cafecito.foundry.runtime.FoundryBindingContext context,
                                    long objectHandle,
                                    games.cafecito.foundry.runtime.ObjectOwnership ownership) {
                                return context.bind(
                                        objectHandle,
                                        ownership,
                                        %s.class,
                                        %s::new);
                            }

                        """
                                .formatted(root.className(), root.className(), root.className()));
        if (root.parentClassName().isEmpty()) {
            source.append(instanceInvocationHelpers());
        }
        appendIdentityMembers(source, root, InvocationStyle.INSTANCE);
        return source.append("}\n").toString();
    }

    private static String singletonRootSource(
            Metadata metadata, String manifestSha256, PublicRoot root) {
        StringBuilder source = publicRootHeader(metadata, manifestSha256, root);
        source.append("public final class ")
                .append(root.className())
                .append(" extends games.cafecito.foundry.runtime.FoundryObject {\n");
        source.append("    private ")
                .append(root.className())
                .append(
                        """
                                (
                                        games.cafecito.foundry.runtime.FoundryBindingContext context,
                                        games.cafecito.foundry.runtime.ObjectLease lease) {
                                super(context, lease);
                            }

                            public static %s bind(
                                    games.cafecito.foundry.runtime.FoundryBindingContext context) {
                                java.util.Objects.requireNonNull(context, "context");
                                if (!context.isAlive()) {
                                    throw new IllegalStateException("Foundry context is no longer alive.");
                                }
                                long objectHandle = context.engine().singleton(
                                        context.contextHandle(), decodeIdentity("%s"));
                                return context.bind(
                                        objectHandle,
                                        games.cafecito.foundry.runtime.ObjectOwnership.BORROWED,
                                        %s.class,
                                        %s::new);
                            }

                        """
                                .formatted(
                                        root.className(),
                                        base64(root.descriptor().rootIdentity()),
                                        root.className(),
                                        root.className()));
        source.append(instanceInvocationHelpers());
        appendIdentityMembers(source, root, InvocationStyle.INSTANCE);
        return source.append("}\n").toString();
    }

    private static String builtinRootSource(
            Metadata metadata, String manifestSha256, PublicRoot root) {
        StringBuilder source = publicRootHeader(metadata, manifestSha256, root);
        source.append("public final class ").append(root.className()).append(" {\n");
        source.append(
                """
                            private final games.cafecito.foundry.types.Variant value;

                        """);
        source.append("    public ")
                .append(root.className())
                .append(
                        """
                                (games.cafecito.foundry.types.Variant value) {
                                this.value = java.util.Objects.requireNonNull(value, "value");
                            }

                            public games.cafecito.foundry.types.Variant value() {
                                return value;
                            }

                        """);
        source.append(builtinInvocationHelpers());
        appendIdentityMembers(source, root, InvocationStyle.BUILTIN);
        return source.append("}\n").toString();
    }

    private static String staticRootSource(
            Metadata metadata, String manifestSha256, PublicRoot root) {
        StringBuilder source = publicRootHeader(metadata, manifestSha256, root);
        source.append("public final class ").append(root.className()).append(" {\n");
        source.append(staticInvocationHelpers());
        appendIdentityMembers(source, root, InvocationStyle.STATIC);
        source.append("    private ").append(root.className()).append("() {}\n");
        return source.append("}\n").toString();
    }

    private static StringBuilder publicRootHeader(
            Metadata metadata, String manifestSha256, PublicRoot root) {
        return new StringBuilder(generatedHeader(metadata, manifestSha256))
                .append("package ")
                .append(root.packageName())
                .append(";\n\n")
                .append("/** Generated public root for an accepted Foundry API identity. */\n");
    }

    private static String instanceInvocationHelpers() {
        return """
                    protected final games.cafecito.foundry.types.Variant invokeGenerated(
                            String encodedIdentity,
                            games.cafecito.foundry.types.Variant... arguments) {
                        String identity = decodeIdentity(encodedIdentity);
                        return call(identity, arguments);
                    }

                    protected static String decodeIdentity(String encoded) {
                        return new String(
                                java.util.Base64.getDecoder().decode(encoded),
                                java.nio.charset.StandardCharsets.UTF_8);
                    }

                """;
    }

    private static String staticInvocationHelpers() {
        return """
                    private static games.cafecito.foundry.types.Variant invokeGenerated(
                            games.cafecito.foundry.runtime.FoundryBindingContext context,
                            String encodedIdentity,
                            games.cafecito.foundry.types.Variant... arguments) {
                        java.util.Objects.requireNonNull(context, "context");
                        if (!context.isAlive()) {
                            throw new IllegalStateException("Foundry context is no longer alive.");
                        }
                        String identity = decodeIdentity(encodedIdentity);
                        return context.call(0, identity, java.util.List.of(arguments));
                    }

                    private static String decodeIdentity(String encoded) {
                        return new String(
                                java.util.Base64.getDecoder().decode(encoded),
                                java.nio.charset.StandardCharsets.UTF_8);
                    }

                """;
    }

    private static String builtinInvocationHelpers() {
        return """
                    private games.cafecito.foundry.types.Variant invokeGenerated(
                            games.cafecito.foundry.runtime.FoundryBindingContext context,
                            String encodedIdentity,
                            games.cafecito.foundry.types.Variant... arguments) {
                        java.util.Objects.requireNonNull(context, "context");
                        if (!context.isAlive()) {
                            throw new IllegalStateException("Foundry context is no longer alive.");
                        }
                        var argumentsWithReceiver =
                                new java.util.ArrayList<games.cafecito.foundry.types.Variant>(
                                        arguments.length + 1);
                        argumentsWithReceiver.add(value);
                        argumentsWithReceiver.addAll(java.util.List.of(arguments));
                        String identity = decodeIdentity(encodedIdentity);
                        return context.call(0, identity, argumentsWithReceiver);
                    }

                    private static String decodeIdentity(String encoded) {
                        return new String(
                                java.util.Base64.getDecoder().decode(encoded),
                                java.nio.charset.StandardCharsets.UTF_8);
                    }

                """;
    }

    private static void appendIdentityMembers(
            StringBuilder source, PublicRoot root, InvocationStyle style) {
        List<FoundryApi.Entity> entities = root.descriptor().entities();
        FoundryApi.Entity rootEntity = entities.get(0);
        source.append("// public-identity-base64 ")
                .append(base64(rootEntity.identity()))
                .append('\n')
                .append("    public static String sourceIdentity() {\n")
                .append("        return decodeIdentity(\"")
                .append(base64(rootEntity.identity()))
                .append("\");\n")
                .append("    }\n\n");
        if (style == InvocationStyle.STATIC
                && root.descriptor().category().equals("utility_functions")) {
            source.append("    public static games.cafecito.foundry.types.Variant call(\n")
                    .append(
                            "            games.cafecito.foundry.runtime.FoundryBindingContext context,\n")
                    .append("            games.cafecito.foundry.types.Variant... arguments) {\n")
                    .append("        return invokeGenerated(context, \"")
                    .append(base64(rootEntity.identity()))
                    .append("\", arguments);\n")
                    .append("    }\n\n");
        }
        for (int index = 1; index < entities.size(); index++) {
            FoundryApi.Entity entity = entities.get(index);
            source.append("// public-identity-base64 ")
                    .append(base64(entity.identity()))
                    .append('\n');
            String methodName =
                    "invoke_"
                            + javaMemberPart(entity.identity())
                            + "_"
                            + sha256(entity.identity()).substring(0, 12);
            if (style == InvocationStyle.INSTANCE) {
                source.append("    public games.cafecito.foundry.types.Variant ")
                        .append(methodName)
                        .append("(games.cafecito.foundry.types.Variant... arguments) {\n")
                        .append("        return invokeGenerated(\"")
                        .append(base64(entity.identity()))
                        .append("\", arguments);\n");
            } else {
                source.append("    public ")
                        .append(style == InvocationStyle.STATIC ? "static " : "")
                        .append("games.cafecito.foundry.types.Variant ")
                        .append(methodName)
                        .append("(\n")
                        .append(
                                "            games.cafecito.foundry.runtime.FoundryBindingContext context,\n")
                        .append(
                                "            games.cafecito.foundry.types.Variant... arguments) {\n")
                        .append("        return invokeGenerated(context, \"")
                        .append(base64(entity.identity()))
                        .append("\", arguments);\n");
            }
            source.append("    }\n\n");
        }
    }

    private static String javaMemberPart(String identity) {
        int separator = identity.lastIndexOf('/');
        String tail = separator < 0 ? identity : identity.substring(separator + 1);
        String value = pascalCase(tail);
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String descriptorSource(
            Metadata metadata, String manifestSha256, Descriptor descriptor) {
        StringBuilder source = new StringBuilder(generatedHeader(metadata, manifestSha256));
        source.append("package ").append(PACKAGE).append(";\n\n");
        source.append("/** Immutable generated metadata for one normalized API root. */\n");
        for (FoundryApi.Entity entity : descriptor.entities()) {
            source.append("// entity-base64 ")
                    .append(base64(entity.identity()))
                    .append(" edge-base64 ")
                    .append(base64(entity.edge()))
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
        return "// Foundry-Java generated metadata; dynamic values use RFC 4648 base64.\n"
                + "// generator-version-base64 "
                + base64(metadata.generatorVersion())
                + "\n"
                + "// foundry-version-base64 "
                + base64(metadata.foundryVersion())
                + "\n"
                + "// foundry-commit-base64 "
                + base64(metadata.foundryCommit())
                + "\n"
                + "// api-sha256-base64 "
                + base64(metadata.apiSha256())
                + "\n"
                + "// interface-header-sha256-base64 "
                + base64(metadata.interfaceHeaderSha256())
                + "\n"
                + "// compatibility-manifest-sha256-base64 "
                + base64(manifestSha256)
                + "\n";
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
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

    private record PublicRoot(
            Descriptor descriptor,
            String packageName,
            String sourcePath,
            String className,
            String parentClassName) {
        PublicRoot withParentClassName(String value) {
            return new PublicRoot(descriptor, packageName, sourcePath, className, value);
        }
    }

    private enum InvocationStyle {
        INSTANCE,
        BUILTIN,
        STATIC
    }
}
