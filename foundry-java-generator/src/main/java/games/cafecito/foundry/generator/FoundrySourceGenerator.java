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
    private static final Set<String> BUILTIN_CLASS_NAMES =
            Set.of(
                    "AABB",
                    "Array",
                    "Basis",
                    "Callable",
                    "Color",
                    "Dictionary",
                    "Nil",
                    "NodePath",
                    "PackedByteArray",
                    "PackedColorArray",
                    "PackedFloat32Array",
                    "PackedFloat64Array",
                    "PackedInt32Array",
                    "PackedInt64Array",
                    "PackedStringArray",
                    "PackedVector2Array",
                    "PackedVector3Array",
                    "PackedVector4Array",
                    "Plane",
                    "Projection",
                    "Quaternion",
                    "RID",
                    "Rect2",
                    "Rect2i",
                    "Signal",
                    "String",
                    "StringName",
                    "Transform2D",
                    "Transform3D",
                    "Vector2",
                    "Vector2i",
                    "Vector3",
                    "Vector3i",
                    "Vector4",
                    "Vector4i",
                    "bool",
                    "float",
                    "int");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    public static String extensionTypeName() {
        return FoundryExtension.class.getName();
    }

    public GeneratedTree generate(
            FoundryApi api, Metadata metadata, CompatibilityManifest manifest) {
        metadata.validate();
        ApiTypeCatalog typeCatalog = ApiTypeCatalog.from(api);
        validateReferencedTypes(api, typeCatalog);
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
                registrationSource(
                        metadata, manifestSha256, descriptors, publicRoots, covered.size()));
        sources.put(
                PACKAGE_PATH + "GeneratedPublicApi.java",
                publicApiSource(metadata, manifestSha256, publicRoots));
        for (Descriptor descriptor : descriptors) {
            String path = PACKAGE_PATH + descriptor.className() + ".java";
            if (sources.put(path, descriptorSource(metadata, manifestSha256, descriptor)) != null) {
                throw new ApiInputException("Generated source path collision: " + path + ".");
            }
        }
        Map<String, List<PublicRoot>> rootsBySourcePath =
                publicRoots.stream()
                        .collect(
                                Collectors.groupingBy(
                                        PublicRoot::sourcePath, TreeMap::new, Collectors.toList()));
        for (var entry : rootsBySourcePath.entrySet()) {
            String path = entry.getKey();
            if (sources.put(
                            path,
                            publicRootSource(
                                    metadata, manifestSha256, List.copyOf(entry.getValue())))
                    != null) {
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
            FoundryApi.Entity rootEntity = descriptor.entities().get(0);
            String sourceName = sourceName(rootEntity);
            String packageSuffix = publicPackageSuffix(descriptor.category());
            String className = pascalCase(sourceName);
            if (descriptor.category().equals("utility_functions")) {
                packageSuffix = "";
                className = "Utilities";
            } else if (descriptor.category().equals("builtin_class_sizes")) {
                className = "BuiltinClassSizes" + pascalCase(sourceName);
            } else if (descriptor.category().equals("builtin_class_member_offsets")) {
                className = "BuiltinClassMemberOffsets" + pascalCase(sourceName);
            }
            PublicRoot root =
                    new PublicRoot(
                            descriptor,
                            packageSuffix.isEmpty() ? PACKAGE : PACKAGE + "." + packageSuffix,
                            PACKAGE_PATH
                                    + (packageSuffix.isEmpty()
                                            ? ""
                                            : packageSuffix.replace('.', '/') + "/")
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
            case "utility_functions" -> "";
            case "global_constants" -> "globals";
            case "global_enums" -> "enums";
            case "builtin_class_sizes", "builtin_class_member_offsets" -> "layout";
            case "native_structures" -> "structures";
            default ->
                    throw new ApiInputException(
                            "No generated public package for API category " + category + ".");
        };
    }

    private static String sourceName(FoundryApi.Entity entity) {
        JsonValue value = entity.source().optional("name");
        if (value != null) {
            return value.requireString(entity.identity() + ".name");
        }
        value = entity.source().optional("build_configuration");
        if (value != null) {
            return value.requireString(entity.identity() + ".build_configuration");
        }
        return entity.identity().substring(entity.identity().lastIndexOf('/') + 1);
    }

    private static String pascalCase(String source) {
        StringBuilder result = new StringBuilder();
        boolean capitalize = true;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '_' || !Character.isJavaIdentifierPart(character)) {
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
            List<PublicRoot> publicRoots,
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
        List<PublicRoot> objectRoots =
                publicRoots.stream()
                        .filter(root -> root.descriptor().category().equals("classes"))
                        .toList();
        source.append(
                """
                            /** Registers every generated object wrapper deterministically. */
                            public static void registerAll(
                                    games.cafecito.foundry.runtime.FoundryBindingContext context) {
                                java.util.Objects.requireNonNull(context, "context");
                        """);
        int registrationChunkCount = (objectRoots.size() + 99) / 100;
        for (int chunk = 0; chunk < registrationChunkCount; chunk++) {
            source.append("        registerObjectChunk").append(chunk).append("(context);\n");
        }
        source.append("    }\n\n");
        for (int chunk = 0; chunk < registrationChunkCount; chunk++) {
            int start = chunk * 100;
            int end = Math.min(start + 100, objectRoots.size());
            source.append("    private static void registerObjectChunk")
                    .append(chunk)
                    .append("(\n")
                    .append(
                            "            games.cafecito.foundry.runtime.FoundryBindingContext context) {\n");
            for (int index = start; index < end; index++) {
                PublicRoot root = objectRoots.get(index);
                source.append("        ")
                        .append(root.packageName())
                        .append('.')
                        .append(root.className())
                        .append(".registerObjectType(context);\n");
            }
            source.append("    }\n\n");
        }
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
            Metadata metadata, String manifestSha256, List<PublicRoot> roots) {
        PublicRoot root = roots.get(0);
        if (root.descriptor().category().equals("utility_functions")) {
            return utilitiesSource(metadata, manifestSha256, roots);
        }
        if (roots.size() != 1) {
            throw new ApiInputException(
                    "Multiple public roots unexpectedly share " + root.sourcePath() + ".");
        }
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
        source.append("    public static void registerObjectType(\n")
                .append(
                        "            games.cafecito.foundry.runtime.FoundryBindingContext context) {\n")
                .append("        context.registerObjectType(\"")
                .append(javaStringBody(sourceName(root.descriptor().entities().get(0))))
                .append("\", ")
                .append(root.className())
                .append(".class, ")
                .append(root.className())
                .append("::new);\n")
                .append("    }\n\n");
        appendPublicIdentityMarkers(source, root.descriptor().entities());
        appendClassMembers(source, root.descriptor().entities().get(0));
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
                                        context.contextHandle(), "%s");
                                return context.bind(
                                        objectHandle,
                                        games.cafecito.foundry.runtime.ObjectOwnership.BORROWED,
                                        %s.class,
                                        %s::new);
                            }

                        """
                                .formatted(
                                        root.className(),
                                        javaStringBody(
                                                sourceName(root.descriptor().entities().get(0))),
                                        root.className(),
                                        root.className()));
        appendPublicIdentityMarkers(source, root.descriptor().entities());
        appendClassMembers(source, root.descriptor().entities().get(0));
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
        appendPublicIdentityMarkers(source, root.descriptor().entities());
        appendBuiltinMembers(source, root.descriptor().entities().get(0), root.className());
        return source.append("}\n").toString();
    }

    private static String staticRootSource(
            Metadata metadata, String manifestSha256, PublicRoot root) {
        StringBuilder source = publicRootHeader(metadata, manifestSha256, root);
        if (root.descriptor().category().equals("global_enums")) {
            return globalEnumSource(metadata, manifestSha256, root);
        }
        source.append("public final class ").append(root.className()).append(" {\n");
        appendPublicIdentityMarkers(source, root.descriptor().entities());
        if (root.descriptor().category().equals("global_constants")) {
            appendConstant(source, root.descriptor().entities().get(0));
        }
        source.append("    private ").append(root.className()).append("() {}\n");
        return source.append("}\n").toString();
    }

    private static String utilitiesSource(
            Metadata metadata, String manifestSha256, List<PublicRoot> roots) {
        StringBuilder source =
                new StringBuilder(generatedHeader(metadata, manifestSha256))
                        .append("package ")
                        .append(PACKAGE)
                        .append(";\n\n")
                        .append("/** Strongly typed Foundry utility functions. */\n")
                        .append("public final class Utilities {\n");
        for (PublicRoot root : roots) {
            appendPublicIdentityMarkers(source, root.descriptor().entities());
            appendTypedMethod(
                    source, root.descriptor().entities().get(0), MethodStyle.STATIC_CONTEXT, false);
        }
        source.append("    private Utilities() {}\n");
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

    private static void appendPublicIdentityMarkers(
            StringBuilder source, List<FoundryApi.Entity> entities) {
        for (FoundryApi.Entity entity : entities) {
            source.append("// public-identity-base64 ")
                    .append(base64(entity.identity()))
                    .append('\n');
        }
        source.append('\n');
    }

    private static void appendClassMembers(StringBuilder source, FoundryApi.Entity root) {
        Set<String> generatedMethodNames =
                root.children().stream()
                        .filter(child -> child.edge().equals("methods"))
                        .map(
                                child -> {
                                    String name =
                                            requiredString(
                                                    child.source(), "name", child.identity());
                                    return javaMethodName(
                                            name, optionalBoolean(child.source(), "is_virtual"));
                                })
                        .collect(Collectors.toSet());
        for (FoundryApi.Entity child : root.children()) {
            switch (child.edge()) {
                case "methods" -> {
                    boolean isStatic = optionalBoolean(child.source(), "is_static");
                    boolean isVirtual = optionalBoolean(child.source(), "is_virtual");
                    appendTypedMethod(
                            source,
                            child,
                            isStatic ? MethodStyle.STATIC_CONTEXT : MethodStyle.INSTANCE,
                            isVirtual);
                }
                case "properties" -> {
                    String getter = optionalString(child.source(), "getter", child.identity());
                    String setter = optionalString(child.source(), "setter", child.identity());
                    appendProperty(
                            source,
                            child,
                            getter != null
                                    && !generatedMethodNames.contains(
                                            javaMethodName(getter, false)),
                            setter != null
                                    && !generatedMethodNames.contains(
                                            javaMethodName(setter, false)));
                }
                case "signals" -> appendSignal(source, child);
                case "constants" -> appendConstant(source, child);
                case "enums" -> appendNestedEnum(source, child);
                default -> {
                    // All remaining identities are represented by deterministic metadata markers.
                }
            }
        }
    }

    private static void appendBuiltinMembers(
            StringBuilder source, FoundryApi.Entity root, String className) {
        Set<String> generatedMethodNames =
                root.children().stream()
                        .filter(child -> child.edge().equals("methods"))
                        .map(
                                child ->
                                        javaMethodName(
                                                requiredString(
                                                        child.source(), "name", child.identity()),
                                                false))
                        .collect(Collectors.toSet());
        for (FoundryApi.Entity child : root.children()) {
            switch (child.edge()) {
                case "methods" -> appendTypedMethod(source, child, MethodStyle.BUILTIN, false);
                case "constructors" -> appendBuiltinConstructor(source, child, className);
                case "operators" -> appendBuiltinOperator(source, child);
                case "members" -> {
                    String getterName =
                            "get"
                                    + pascalCase(
                                            requiredString(
                                                    child.source(), "name", child.identity()));
                    if (!generatedMethodNames.contains(getterName)) {
                        appendBuiltinMember(source, child);
                    }
                }
                case "constants" -> appendConstant(source, child);
                case "enums" -> appendNestedEnum(source, child);
                default -> {
                    // All remaining identities are represented by deterministic metadata markers.
                }
            }
        }
    }

    private static void appendTypedMethod(
            StringBuilder source, FoundryApi.Entity method, MethodStyle style, boolean virtual) {
        List<FoundryApi.Entity> arguments = children(method, "arguments");
        int requiredCount = arguments.size();
        while (requiredCount > 0
                && arguments.get(requiredCount - 1).source().optional("default_value") != null) {
            requiredCount--;
        }
        for (int argumentCount = arguments.size();
                argumentCount >= requiredCount;
                argumentCount--) {
            appendTypedMethodOverload(source, method, style, virtual, arguments, argumentCount);
        }
    }

    private static void appendTypedMethodOverload(
            StringBuilder source,
            FoundryApi.Entity method,
            MethodStyle style,
            boolean virtual,
            List<FoundryApi.Entity> arguments,
            int argumentCount) {
        String foundryName = requiredString(method.source(), "name", method.identity());
        String methodName = javaMethodName(foundryName, virtual);
        String returnFoundryType = returnFoundryType(method);
        String returnJavaType = javaType(returnFoundryType);
        source.append("    /** Calls ")
                .append(javaStringBody(foundryName))
                .append(". */\n")
                .append("    ")
                .append(virtual ? "protected " : "public ")
                .append(style == MethodStyle.STATIC_CONTEXT ? "static " : "")
                .append(returnJavaType)
                .append(' ')
                .append(methodName)
                .append('(');
        boolean needsContext = style == MethodStyle.STATIC_CONTEXT || style == MethodStyle.BUILTIN;
        if (needsContext) {
            source.append("games.cafecito.foundry.runtime.FoundryBindingContext context");
        }
        for (int index = 0; index < argumentCount; index++) {
            if (needsContext || index > 0) {
                source.append(", ");
            }
            FoundryApi.Entity argument = arguments.get(index);
            String foundryType = requiredString(argument.source(), "type", argument.identity());
            source.append(javaType(foundryType))
                    .append(' ')
                    .append(
                            javaParameterName(
                                    requiredString(
                                            argument.source(), "name", argument.identity())));
        }
        boolean vararg = optionalBoolean(method.source(), "is_vararg");
        if (vararg) {
            if (needsContext || argumentCount > 0) {
                source.append(", ");
            }
            source.append("games.cafecito.foundry.types.Variant... varargs");
        }
        source.append(") {\n");
        appendInvocation(
                source,
                method,
                style,
                arguments.subList(0, argumentCount),
                vararg,
                returnFoundryType);
        source.append("    }\n\n");
    }

    private static void appendInvocation(
            StringBuilder source,
            FoundryApi.Entity callable,
            MethodStyle style,
            List<FoundryApi.Entity> arguments,
            boolean vararg,
            String returnFoundryType) {
        source.append("        var __foundryGeneratedCallArguments = new java.util.ArrayList<")
                .append("games.cafecito.foundry.types.Variant>();\n");
        if (style == MethodStyle.BUILTIN) {
            source.append("        __foundryGeneratedCallArguments.add(this.value);\n");
        }
        for (FoundryApi.Entity argument : arguments) {
            String name =
                    javaParameterName(
                            requiredString(argument.source(), "name", argument.identity()));
            String foundryType = requiredString(argument.source(), "type", argument.identity());
            source.append("        __foundryGeneratedCallArguments.add(")
                    .append(encodeExpression(foundryType, name))
                    .append(");\n");
        }
        if (vararg) {
            source.append(
                    "        __foundryGeneratedCallArguments.addAll(java.util.List.of(varargs));\n");
        }
        if (style == MethodStyle.INSTANCE) {
            source.append(
                            "        games.cafecito.foundry.types.Variant"
                                    + " __foundryGeneratedResult = call(\"")
                    .append(javaStringBody(callable.identity()))
                    .append(
                            "\", __foundryGeneratedCallArguments.toArray("
                                    + "games.cafecito.foundry.types.Variant[]::new));\n");
        } else {
            source.append(
                            "        games.cafecito.foundry.types.Variant"
                                    + " __foundryGeneratedResult = context.call(0, \"")
                    .append(javaStringBody(callable.identity()))
                    .append("\", __foundryGeneratedCallArguments);\n");
        }
        appendReturn(source, returnFoundryType, "__foundryGeneratedResult");
    }

    private static void appendBuiltinConstructor(
            StringBuilder source, FoundryApi.Entity constructor, String className) {
        List<FoundryApi.Entity> arguments = children(constructor, "arguments");
        source.append("    /** Constructs a ")
                .append(className)
                .append(". */\n")
                .append("    public static ")
                .append(className)
                .append(" create(games.cafecito.foundry.runtime.FoundryBindingContext context");
        for (FoundryApi.Entity argument : arguments) {
            String foundryType = requiredString(argument.source(), "type", argument.identity());
            source.append(", ")
                    .append(javaType(foundryType))
                    .append(' ')
                    .append(
                            javaParameterName(
                                    requiredString(
                                            argument.source(), "name", argument.identity())));
        }
        source.append(") {\n")
                .append("        var __foundryGeneratedCallArguments = new java.util.ArrayList<")
                .append("games.cafecito.foundry.types.Variant>();\n");
        for (FoundryApi.Entity argument : arguments) {
            String name =
                    javaParameterName(
                            requiredString(argument.source(), "name", argument.identity()));
            source.append("        __foundryGeneratedCallArguments.add(")
                    .append(
                            encodeExpression(
                                    requiredString(argument.source(), "type", argument.identity()),
                                    name))
                    .append(");\n");
        }
        source.append("        return new ")
                .append(className)
                .append("(context.call(0, \"")
                .append(javaStringBody(constructor.identity()))
                .append("\", __foundryGeneratedCallArguments));\n")
                .append("    }\n\n");
    }

    private static void appendBuiltinOperator(StringBuilder source, FoundryApi.Entity operator) {
        String operatorName = requiredString(operator.source(), "name", operator.identity());
        String methodName =
                switch (operatorName) {
                    case "==" -> "equalTo";
                    case "!=" -> "notEqualTo";
                    case "<" -> "lessThan";
                    case "<=" -> "lessThanOrEqual";
                    case ">" -> "greaterThan";
                    case ">=" -> "greaterThanOrEqual";
                    case "+" -> "add";
                    case "-" -> "subtract";
                    case "*" -> "multiply";
                    case "/" -> "divide";
                    case "%" -> "remainder";
                    case "**" -> "power";
                    case "unary-" -> "negated";
                    case "unary+" -> "positive";
                    case "~" -> "bitwiseNot";
                    case "<<" -> "shiftLeft";
                    case ">>" -> "shiftRight";
                    case "&" -> "bitwiseAnd";
                    case "|" -> "bitwiseOr";
                    case "^" -> "bitwiseXor";
                    case "and" -> "logicalAnd";
                    case "or" -> "logicalOr";
                    case "xor" -> "logicalXor";
                    case "not" -> "logicalNot";
                    case "in" -> "isIn";
                    default -> "operator" + pascalCase(operatorName);
                };
        String returnType = requiredString(operator.source(), "return_type", operator.identity());
        JsonValue rightTypeValue = operator.source().optional("right_type");
        source.append("    /** Applies the ")
                .append(javaDocText(operatorName))
                .append(" operator. */\n")
                .append("    public ")
                .append(javaType(returnType))
                .append(' ')
                .append(methodName)
                .append("(games.cafecito.foundry.runtime.FoundryBindingContext context");
        String rightType = null;
        if (rightTypeValue != null) {
            rightType = rightTypeValue.requireString(operator.identity() + ".right_type");
            source.append(", ").append(javaType(rightType)).append(" right");
        }
        source.append(") {\n")
                .append("        var __foundryGeneratedCallArguments = new java.util.ArrayList<")
                .append("games.cafecito.foundry.types.Variant>();\n")
                .append("        __foundryGeneratedCallArguments.add(value);\n");
        if (rightType != null) {
            source.append("        __foundryGeneratedCallArguments.add(")
                    .append(encodeExpression(rightType, "right"))
                    .append(");\n");
        }
        source.append("        var __foundryGeneratedResult = context.call(0, \"")
                .append(javaStringBody(operator.identity()))
                .append("\", __foundryGeneratedCallArguments);\n");
        appendReturn(source, returnType, "__foundryGeneratedResult");
        source.append("    }\n\n");
    }

    private static void appendProperty(
            StringBuilder source,
            FoundryApi.Entity property,
            boolean generateGetter,
            boolean generateSetter) {
        String name = requiredString(property.source(), "name", property.identity());
        String type = requiredString(property.source(), "type", property.identity());
        String suffix = pascalCase(name);
        if (generateGetter) {
            source.append("    /** Gets ")
                    .append(javaStringBody(name))
                    .append(". */\n")
                    .append("    public ")
                    .append(javaType(type))
                    .append(" get")
                    .append(suffix)
                    .append("() {\n")
                    .append("        var __foundryGeneratedResult = call(\"")
                    .append(javaStringBody(property.identity()))
                    .append("\");\n");
            appendReturn(source, type, "__foundryGeneratedResult");
            source.append("    }\n\n");
        }
        if (generateSetter) {
            source.append("    /** Sets ")
                    .append(javaStringBody(name))
                    .append(". */\n")
                    .append("    public void set")
                    .append(suffix)
                    .append('(')
                    .append(javaType(type))
                    .append(' ')
                    .append(javaParameterName(name))
                    .append(") {\n")
                    .append("        call(\"")
                    .append(javaStringBody(property.identity()))
                    .append("\", ")
                    .append(encodeExpression(type, javaParameterName(name)))
                    .append(");\n")
                    .append("    }\n\n");
        }
    }

    private static void appendSignal(StringBuilder source, FoundryApi.Entity signal) {
        String name = requiredString(signal.source(), "name", signal.identity());
        source.append("    /** Gets the ")
                .append(javaStringBody(name))
                .append(" signal. */\n")
                .append("    public games.cafecito.foundry.runtime.FoundrySignal ")
                .append(camelCase(name))
                .append("Signal() {\n")
                .append("        return call(\"")
                .append(javaStringBody(signal.identity()))
                .append("\").asSignal();\n")
                .append("    }\n\n");
    }

    private static void appendBuiltinMember(StringBuilder source, FoundryApi.Entity member) {
        String name = requiredString(member.source(), "name", member.identity());
        String type = requiredString(member.source(), "type", member.identity());
        source.append("    /** Gets ")
                .append(javaStringBody(name))
                .append(". */\n")
                .append("    public ")
                .append(javaType(type))
                .append(" get")
                .append(pascalCase(name))
                .append("(games.cafecito.foundry.runtime.FoundryBindingContext context) {\n")
                .append("        var __foundryGeneratedResult = context.call(0, \"")
                .append(javaStringBody(member.identity()))
                .append("\", java.util.List.of(value));\n");
        appendReturn(source, type, "__foundryGeneratedResult");
        source.append("    }\n\n");
    }

    private static void appendConstant(StringBuilder source, FoundryApi.Entity constant) {
        String name = requiredString(constant.source(), "name", constant.identity());
        JsonValue raw = constant.source().optional("value");
        String value = raw == null ? "0" : raw.canonicalJson().replace("\"", "");
        JsonValue typeValue = constant.source().optional("type");
        String foundryType =
                typeValue == null ? "int" : typeValue.requireString(constant.identity() + ".type");
        source.append("    /** Foundry constant ").append(javaStringBody(name)).append(". */\n");
        if (foundryType.equals("int") && value.matches("-?[0-9]+")) {
            source.append("    public static final long ")
                    .append(javaConstant(name))
                    .append(" = ")
                    .append(value)
                    .append("L;\n\n");
        } else {
            source.append("    public static final games.cafecito.foundry.runtime.FoundryConstant<")
                    .append(javaType(foundryType))
                    .append("> ")
                    .append(javaConstant(name))
                    .append(" = new games.cafecito.foundry.runtime.FoundryConstant<>(\"")
                    .append(javaStringBody(constant.identity()))
                    .append("\", value -> ")
                    .append(decodeExpression(foundryType, "value"))
                    .append(");\n\n");
        }
    }

    private static void appendNestedEnum(StringBuilder source, FoundryApi.Entity enumEntity) {
        String name =
                pascalCase(requiredString(enumEntity.source(), "name", enumEntity.identity()));
        if (optionalBoolean(enumEntity.source(), "is_bitfield")) {
            source.append("    /** Bitfield values for ")
                    .append(name)
                    .append(". */\n")
                    .append("    public static final class ")
                    .append(name)
                    .append(" {\n");
            for (FoundryApi.Entity value : children(enumEntity, "values")) {
                appendConstant(source, value);
            }
            source.append("        private ").append(name).append("() {}\n    }\n\n");
            return;
        }
        source.append("    /** Values for ")
                .append(name)
                .append(". */\n")
                .append("    public enum ")
                .append(name)
                .append(" {\n");
        List<FoundryApi.Entity> values = children(enumEntity, "values");
        for (int index = 0; index < values.size(); index++) {
            FoundryApi.Entity value = values.get(index);
            source.append("        ")
                    .append(javaConstant(requiredString(value.source(), "name", value.identity())))
                    .append('(')
                    .append(value.source().require("value", value.identity()).canonicalJson())
                    .append("L)")
                    .append(index + 1 == values.size() ? ";\n" : ",\n");
        }
        appendEnumBody(source, name);
        source.append("    }\n\n");
    }

    private static String globalEnumSource(
            Metadata metadata, String manifestSha256, PublicRoot root) {
        FoundryApi.Entity enumEntity = root.descriptor().entities().get(0);
        StringBuilder source =
                new StringBuilder(generatedHeader(metadata, manifestSha256))
                        .append("package ")
                        .append(root.packageName())
                        .append(";\n\n");
        appendPublicIdentityMarkers(source, root.descriptor().entities());
        String name = root.className();
        if (optionalBoolean(enumEntity.source(), "is_bitfield")) {
            source.append("/** Foundry global bitfield ")
                    .append(javaStringBody(sourceName(enumEntity)))
                    .append(". */\n")
                    .append("public final class ")
                    .append(name)
                    .append(" {\n");
            for (FoundryApi.Entity value : children(enumEntity, "values")) {
                appendConstant(source, value);
            }
            return source.append("    private ").append(name).append("() {}\n}\n").toString();
        }
        source.append("/** Foundry global enum ")
                .append(javaStringBody(sourceName(enumEntity)))
                .append(". */\n")
                .append("public enum ")
                .append(name)
                .append(" {\n");
        List<FoundryApi.Entity> values = children(enumEntity, "values");
        for (int index = 0; index < values.size(); index++) {
            FoundryApi.Entity value = values.get(index);
            source.append("    ")
                    .append(javaConstant(requiredString(value.source(), "name", value.identity())))
                    .append('(')
                    .append(value.source().require("value", value.identity()).canonicalJson())
                    .append("L)")
                    .append(index + 1 == values.size() ? ";\n" : ",\n");
        }
        source.append(
                """

                    private final long value;

                    %s(long value) {
                        this.value = value;
                    }

                    public long value() {
                        return value;
                    }

                    public static %s fromValue(long value) {
                        for (%s candidate : values()) {
                            if (candidate.value == value) {
                                return candidate;
                            }
                        }
                        throw new IllegalArgumentException("Unknown %s value " + value + ".");
                    }
                }
                """
                        .formatted(name, name, name, name));
        return source.toString();
    }

    private static void appendEnumBody(StringBuilder source, String name) {
        source.append(
                """

                        private final long value;

                        %s(long value) {
                            this.value = value;
                        }

                        public long value() {
                            return value;
                        }

                        public static %s fromValue(long value) {
                            for (%s candidate : values()) {
                                if (candidate.value == value) {
                                    return candidate;
                                }
                            }
                            throw new IllegalArgumentException("Unknown %s value " + value + ".");
                        }
                """
                        .formatted(name, name, name, name));
    }

    private static List<FoundryApi.Entity> children(FoundryApi.Entity entity, String edge) {
        return entity.children().stream().filter(child -> child.edge().equals(edge)).toList();
    }

    private static String returnFoundryType(FoundryApi.Entity callable) {
        JsonValue direct = callable.source().optional("return_type");
        if (direct != null) {
            return direct.requireString(callable.identity() + ".return_type");
        }
        List<FoundryApi.Entity> values = children(callable, "return_value");
        return values.isEmpty()
                ? "void"
                : requiredString(values.get(0).source(), "type", values.get(0).identity());
    }

    private static void validateReferencedTypes(FoundryApi api, ApiTypeCatalog catalog) {
        for (FoundryApi.Entity entity : api.entities()) {
            if (entity.identity().startsWith("singletons/")
                    && entity.identity().indexOf('/', "singletons/".length()) < 0) {
                continue;
            }
            for (String field : List.of("type", "return_type", "right_type")) {
                JsonValue value = entity.source().optional(field);
                if (value != null) {
                    validateReferencedType(
                            value.requireString(entity.identity() + "." + field),
                            entity.identity() + "." + field,
                            catalog);
                }
            }
        }
    }

    private static void validateReferencedType(
            String foundryType, String location, ApiTypeCatalog catalog) {
        if (foundryType.isBlank()) {
            throw new ApiInputException(location + " has a blank API type.");
        }
        if (foundryType.endsWith("*")) {
            return;
        }
        if (foundryType.startsWith("typedarray::")) {
            String elementType = foundryType.substring("typedarray::".length());
            int encodedSeparator = elementType.indexOf(':');
            if (encodedSeparator >= 0) {
                elementType = elementType.substring(encodedSeparator + 1);
            }
            validateReferencedType(
                    elementType.isBlank() ? "Variant" : elementType,
                    location + " array element",
                    catalog);
            return;
        }
        if (foundryType.startsWith("typeddictionary::")) {
            String[] types = foundryType.substring("typeddictionary::".length()).split(";", -1);
            if (types.length != 2 || types[0].isBlank() || types[1].isBlank()) {
                throw new ApiInputException(
                        "Malformed typed dictionary API type "
                                + foundryType
                                + " at "
                                + location
                                + ".");
            }
            validateReferencedType(types[0], location + " dictionary key", catalog);
            validateReferencedType(types[1], location + " dictionary value", catalog);
            return;
        }
        if (foundryType.contains(",")) {
            for (String constraint : foundryType.split(",")) {
                String candidate = constraint.trim();
                if (candidate.startsWith("-")) {
                    candidate = candidate.substring(1);
                }
                if (!candidate.isBlank()) {
                    validateReferencedType(candidate, location + " constraint", catalog);
                }
            }
            return;
        }
        if (foundryType.startsWith("enum::") || foundryType.startsWith("bitfield::")) {
            String symbol = foundryType.substring(foundryType.indexOf("::") + 2);
            if (!catalog.enumSymbols().contains(symbol)) {
                throw new ApiInputException(
                        "Unknown Foundry enum symbol " + symbol + " at " + location + ".");
            }
            return;
        }
        if (Set.of("void", "Nil", "Variant").contains(foundryType)
                || catalog.classNames().contains(foundryType)
                || catalog.builtinNames().contains(foundryType)
                || catalog.classNames().stream()
                        .anyMatch(name -> pascalCase(name).equals(pascalCase(foundryType)))
                || catalog.builtinNames().stream()
                        .anyMatch(name -> pascalCase(name).equals(pascalCase(foundryType)))
                || BUILTIN_CLASS_NAMES.contains(foundryType)) {
            return;
        }
        if (catalog.nativeStructureNames().contains(foundryType)) {
            throw new ApiInputException(
                    "Native structure "
                            + foundryType
                            + " must be represented by an opaque pointer type at "
                            + location
                            + ".");
        }
        throw new ApiInputException(
                "Unknown Foundry API type " + foundryType + " at " + location + ".");
    }

    private static String javaType(String foundryType) {
        if (foundryType == null || foundryType.equals("void") || foundryType.equals("Nil")) {
            return "void";
        }
        if (foundryType.startsWith("typedarray::")) {
            String elementType = foundryType.substring("typedarray::".length());
            int encodedSeparator = elementType.indexOf(':');
            if (encodedSeparator >= 0) {
                elementType = elementType.substring(encodedSeparator + 1);
            }
            if (elementType.isBlank()) {
                elementType = "Variant";
            }
            return "games.cafecito.foundry.types.FoundryArray<" + boxedJavaType(elementType) + ">";
        }
        if (foundryType.startsWith("typeddictionary::")) {
            String[] types = foundryType.substring("typeddictionary::".length()).split(";", -1);
            if (types.length != 2 || types[0].isBlank() || types[1].isBlank()) {
                throw new ApiInputException(
                        "Malformed typed dictionary API type " + foundryType + ".");
            }
            return "games.cafecito.foundry.types.FoundryDictionary<"
                    + boxedJavaType(types[0])
                    + ", "
                    + boxedJavaType(types[1])
                    + ">";
        }
        if (foundryType.contains(",")) {
            List<String> positive =
                    java.util.Arrays.stream(foundryType.split(","))
                            .map(String::trim)
                            .filter(value -> !value.isBlank() && !value.startsWith("-"))
                            .toList();
            if (positive.size() == 1) {
                return javaType(positive.get(0));
            }
            if (positive.isEmpty()) {
                throw new ApiInputException(
                        "Object constraint has no accepted type: " + foundryType);
            }
            return "games.cafecito.foundry.runtime.FoundryObject";
        }
        if (foundryType.endsWith("*")) {
            return "games.cafecito.foundry.runtime.FoundryNativeHandle";
        }
        if (foundryType.startsWith("enum::")) {
            String enumName = foundryType.substring("enum::".length());
            if (enumName.contains(".")) {
                String[] parts = enumName.split("\\.", 2);
                if (parts[0].equals("Variant")) {
                    return PACKAGE + ".enums." + pascalCase(parts[0] + "_" + parts[1]);
                }
                if (BUILTIN_CLASS_NAMES.contains(parts[0])) {
                    return PACKAGE
                            + ".builtins."
                            + pascalCase(parts[0])
                            + "."
                            + pascalCase(parts[1]);
                }
                return PACKAGE + ".classes." + pascalCase(parts[0]) + "." + pascalCase(parts[1]);
            }
            return PACKAGE + ".enums." + pascalCase(enumName);
        }
        if (foundryType.startsWith("bitfield::")) {
            return "long";
        }
        return switch (foundryType) {
            case "bool" -> "boolean";
            case "int" -> "long";
            case "float" -> "double";
            case "String" -> "java.lang.String";
            case "Variant" -> "games.cafecito.foundry.types.Variant";
            case "Vector2",
                            "Vector2i",
                            "Rect2",
                            "Rect2i",
                            "Vector3",
                            "Vector3i",
                            "Transform2D",
                            "Vector4",
                            "Vector4i",
                            "Plane",
                            "Quaternion",
                            "Basis",
                            "Transform3D",
                            "Projection",
                            "Color",
                            "StringName",
                            "NodePath" ->
                    "games.cafecito.foundry.types." + foundryType;
            case "AABB" -> "games.cafecito.foundry.types.Aabb";
            case "RID" -> "games.cafecito.foundry.types.Rid";
            case "Callable" -> "games.cafecito.foundry.runtime.FoundryCallable";
            case "Signal" -> "games.cafecito.foundry.runtime.FoundrySignal";
            case "Array", "Dictionary" -> PACKAGE + ".builtins." + foundryType;
            case "PackedByteArray",
                            "PackedInt32Array",
                            "PackedInt64Array",
                            "PackedFloat32Array",
                            "PackedFloat64Array",
                            "PackedStringArray",
                            "PackedVector2Array",
                            "PackedVector3Array",
                            "PackedColorArray",
                            "PackedVector4Array" ->
                    "games.cafecito.foundry.types." + foundryType;
            default -> PACKAGE + ".classes." + pascalCase(foundryType);
        };
    }

    private static String boxedJavaType(String foundryType) {
        return switch (javaType(foundryType)) {
            case "boolean" -> "java.lang.Boolean";
            case "long" -> "java.lang.Long";
            case "double" -> "java.lang.Double";
            default -> javaType(foundryType);
        };
    }

    private static String encodeExpression(String foundryType, String expression) {
        String javaType = javaType(foundryType);
        if (foundryType.startsWith("enum::")) {
            return "games.cafecito.foundry.types.Variant.of(" + expression + ".value())";
        }
        if (javaType.startsWith(PACKAGE + ".builtins.")) {
            return expression + ".value()";
        }
        if (javaType.startsWith(PACKAGE + ".classes.")
                || javaType.equals("games.cafecito.foundry.runtime.FoundryObject")) {
            return "games.cafecito.foundry.types.Variant.ofObject(" + expression + ")";
        }
        if (javaType.equals("games.cafecito.foundry.runtime.FoundryNativeHandle")) {
            return "games.cafecito.foundry.types.Variant.of(" + expression + ".bridgeHandle())";
        }
        return "games.cafecito.foundry.types.Variant.of(" + expression + ")";
    }

    private static void appendReturn(
            StringBuilder source, String foundryType, String resultExpression) {
        String type = javaType(foundryType);
        if (type.equals("void")) {
            source.append("        return;\n");
            return;
        }
        source.append("        return ")
                .append(decodeExpression(foundryType, resultExpression))
                .append(";\n");
    }

    private static String decodeExpression(String foundryType, String resultExpression) {
        String type = javaType(foundryType);
        return switch (foundryType) {
            case "bool" -> resultExpression + ".asBoolean()";
            case "int" -> resultExpression + ".asLong()";
            case "float" -> resultExpression + ".asDouble()";
            case "String" -> resultExpression + ".asString()";
            case "Variant" -> resultExpression;
            case "Vector2" -> resultExpression + ".asVector2()";
            case "Vector2i" -> resultExpression + ".asVector2i()";
            case "Rect2" -> resultExpression + ".asRect2()";
            case "Rect2i" -> resultExpression + ".asRect2i()";
            case "Vector3" -> resultExpression + ".asVector3()";
            case "Vector3i" -> resultExpression + ".asVector3i()";
            case "Transform2D" -> resultExpression + ".asTransform2D()";
            case "Vector4" -> resultExpression + ".asVector4()";
            case "Vector4i" -> resultExpression + ".asVector4i()";
            case "Plane" -> resultExpression + ".asPlane()";
            case "Quaternion" -> resultExpression + ".asQuaternion()";
            case "AABB" -> resultExpression + ".asAabb()";
            case "Basis" -> resultExpression + ".asBasis()";
            case "Transform3D" -> resultExpression + ".asTransform3D()";
            case "Projection" -> resultExpression + ".asProjection()";
            case "Color" -> resultExpression + ".asColor()";
            case "StringName" -> resultExpression + ".asStringName()";
            case "NodePath" -> resultExpression + ".asNodePath()";
            case "RID" -> resultExpression + ".asRid()";
            case "Callable" -> resultExpression + ".asCallable()";
            case "Signal" -> resultExpression + ".asSignal()";
            default -> {
                if (foundryType.startsWith("bitfield::")) {
                    yield resultExpression + ".asLong()";
                }
                if (foundryType.startsWith("enum::")) {
                    yield type + ".fromValue(" + resultExpression + ".asLong())";
                }
                if (type.startsWith(PACKAGE + ".builtins.")) {
                    yield "new " + type + "(" + resultExpression + ")";
                }
                if (type.startsWith(PACKAGE + ".classes.")) {
                    yield "(" + type + ") " + resultExpression + ".asObject()";
                }
                if (type.equals("games.cafecito.foundry.runtime.FoundryObject")) {
                    yield resultExpression + ".asObject()";
                }
                if (type.equals("games.cafecito.foundry.runtime.FoundryNativeHandle")) {
                    yield "new games.cafecito.foundry.runtime.FoundryNativeHandle(\""
                            + javaStringBody(foundryType)
                            + "\", "
                            + resultExpression
                            + ".asLong())";
                }
                yield "("
                        + type
                        + ") "
                        + resultExpression
                        + ".as("
                        + variantTypeConstant(foundryType)
                        + ")";
            }
        };
    }

    private static String variantTypeConstant(String foundryType) {
        String normalized =
                foundryType.startsWith("typedarray::")
                        ? "ARRAY"
                        : foundryType.startsWith("typeddictionary::")
                                ? "DICTIONARY"
                                : foundryType
                                        .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                                        .toUpperCase();
        return "games.cafecito.foundry.types.VariantType." + normalized;
    }

    static String javaTypeForTesting(String foundryType) {
        return javaType(foundryType);
    }

    private record ApiTypeCatalog(
            Set<String> classNames,
            Set<String> builtinNames,
            Set<String> nativeStructureNames,
            Set<String> enumSymbols) {
        private static ApiTypeCatalog from(FoundryApi api) {
            Set<String> classNames = new java.util.HashSet<>();
            Set<String> builtinNames = new java.util.HashSet<>();
            Set<String> nativeStructureNames = new java.util.HashSet<>();
            Set<String> enumSymbols = new java.util.HashSet<>();
            for (FoundryApi.Entity root : api.categories().getOrDefault("classes", List.of())) {
                String owner = sourceName(root);
                classNames.add(owner);
                children(root, "enums")
                        .forEach(value -> enumSymbols.add(owner + "." + sourceName(value)));
            }
            for (FoundryApi.Entity root :
                    api.categories().getOrDefault("builtin_classes", List.of())) {
                String owner = sourceName(root);
                builtinNames.add(owner);
                children(root, "enums")
                        .forEach(value -> enumSymbols.add(owner + "." + sourceName(value)));
            }
            api.categories()
                    .getOrDefault("native_structures", List.of())
                    .forEach(value -> nativeStructureNames.add(sourceName(value)));
            api.categories()
                    .getOrDefault("global_enums", List.of())
                    .forEach(value -> enumSymbols.add(sourceName(value)));
            return new ApiTypeCatalog(
                    Set.copyOf(classNames),
                    Set.copyOf(builtinNames),
                    Set.copyOf(nativeStructureNames),
                    Set.copyOf(enumSymbols));
        }
    }

    private static String requiredString(
            JsonValue.JsonObject object, String field, String identity) {
        return object.require(field, identity).requireString(identity + "." + field);
    }

    private static boolean optionalBoolean(JsonValue.JsonObject object, String field) {
        JsonValue value = object.optional(field);
        return value != null && value.requireBoolean(field);
    }

    private static String optionalString(
            JsonValue.JsonObject object, String field, String identity) {
        JsonValue value = object.optional(field);
        return value == null ? null : value.requireString(identity + "." + field);
    }

    private static String camelCase(String source) {
        String pascal = pascalCase(source);
        return javaIdentifier(Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1));
    }

    private static String javaMethodName(String foundryName, boolean virtual) {
        if (virtual) {
            return "on" + pascalCase(foundryName);
        }
        String candidate =
                foundryName.equals("get_class") ? "getFoundryClass" : camelCase(foundryName);
        return switch (candidate) {
            case "close", "wait", "notify", "notifyAll", "isAlive", "context", "objectHandle" ->
                    "foundry" + pascalCase(candidate);
            default -> candidate;
        };
    }

    private static String javaParameterName(String source) {
        String candidate = javaIdentifier(source);
        return candidate.startsWith("__foundryGenerated") ? candidate + "Argument" : candidate;
    }

    private static String javaIdentifier(String source) {
        String candidate = camelIdentifier(source);
        return switch (candidate) {
            case "abstract",
                            "assert",
                            "boolean",
                            "break",
                            "byte",
                            "case",
                            "catch",
                            "char",
                            "class",
                            "const",
                            "continue",
                            "default",
                            "do",
                            "double",
                            "else",
                            "enum",
                            "extends",
                            "final",
                            "finally",
                            "float",
                            "for",
                            "goto",
                            "if",
                            "implements",
                            "import",
                            "instanceof",
                            "int",
                            "interface",
                            "long",
                            "native",
                            "new",
                            "package",
                            "private",
                            "protected",
                            "public",
                            "return",
                            "short",
                            "static",
                            "strictfp",
                            "super",
                            "switch",
                            "synchronized",
                            "this",
                            "throw",
                            "throws",
                            "transient",
                            "try",
                            "void",
                            "volatile",
                            "while",
                            "record",
                            "sealed",
                            "permits",
                            "yield",
                            "var" ->
                    candidate + "Value";
            default -> candidate;
        };
    }

    private static String camelIdentifier(String source) {
        String pascal = pascalCase(source);
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    private static String javaConstant(String source) {
        String value =
                source.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                        .replaceAll("[^A-Za-z0-9_]", "_")
                        .toUpperCase();
        return value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))
                ? "_" + value
                : value;
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

    private static String javaDocText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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

    private enum MethodStyle {
        INSTANCE,
        BUILTIN,
        STATIC_CONTEXT
    }
}
