package games.cafecito.foundry.gradle;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Strict parser and whole-graph validator for Foundry-Java format-2 metadata. */
final class DescriptorValidator {
    private static final List<String> HEADERS =
            List.of(
                    "format",
                    "module",
                    "registry",
                    "api_sha256",
                    "generator_version",
                    "runtime_contract_version",
                    "bridge_contract_version");
    private static final Set<String> ENTRY_KINDS =
            Set.of("class", "constant", "method", "override", "property", "signal");
    private static final Pattern MODULE = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z][a-z0-9]*)*");
    private static final Pattern QUALIFIED_JAVA_NAME =
            Pattern.compile(
                    "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
                            + "(?:\\.[\\p{javaJavaIdentifierStart}]"
                            + "[\\p{javaJavaIdentifierPart}]*)+");
    private static final Pattern JAVA_NAME =
            Pattern.compile("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*");
    private static final Pattern EXPORTED_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION = Pattern.compile("[1-9][0-9]*");
    private static final Set<String> INITIALIZATION_LEVELS =
            Set.of("CORE", "SERVERS", "SCENE", "EDITOR");
    private static final Set<String> PRIMITIVE_TYPES =
            Set.of("boolean", "byte", "short", "int", "long", "char", "float", "double");
    private static final Set<String> INTEGRAL_TYPES =
            Set.of("byte", "short", "int", "long", "char");
    private static final Pattern ENCODED_TEXT = Pattern.compile("[A-Za-z0-9_-]*");
    private static final Pattern CANONICAL_LONG = Pattern.compile("0|-?[1-9][0-9]*");
    private static final Pattern CANONICAL_INDEX = Pattern.compile("-1|0|[1-9][0-9]*");

    private DescriptorValidator() {}

    static FoundryDescriptor parse(String artifact, String contents) {
        return parse(artifact, "", contents);
    }

    static FoundryDescriptor parse(String artifact, String descriptorPath, String contents) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(descriptorPath, "descriptorPath");
        Objects.requireNonNull(contents, "contents");
        List<String> values = new ArrayList<>(HEADERS.size());
        List<FoundryDescriptor.Entry> entries = new ArrayList<>();
        int expectedHeader = 0;
        for (String line : contents.split("\\R", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw invalid(artifact, descriptorPath, "malformed line " + line);
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (expectedHeader < HEADERS.size()) {
                String expected = HEADERS.get(expectedHeader);
                if (!key.equals(expected)) {
                    throw invalid(
                            artifact,
                            descriptorPath,
                            "expected field " + expected + " but found " + line);
                }
                if (value.isBlank()) {
                    throw invalid(artifact, descriptorPath, "blank field " + key);
                }
                values.add(value);
                expectedHeader++;
                continue;
            }
            if (!ENTRY_KINDS.contains(key)) {
                throw invalid(artifact, descriptorPath, "unknown entry " + line);
            }
            validateEntry(artifact, descriptorPath, key, value);
            entries.add(new FoundryDescriptor.Entry(key, value));
        }
        if (expectedHeader < HEADERS.size()) {
            throw invalid(artifact, descriptorPath, "missing field " + HEADERS.get(expectedHeader));
        }
        validateHeader(artifact, descriptorPath, HEADERS.get(0), values.get(0), "2");
        validatePattern(artifact, descriptorPath, "module", values.get(1), MODULE);
        validatePattern(artifact, descriptorPath, "registry", values.get(2), QUALIFIED_JAVA_NAME);
        validatePattern(artifact, descriptorPath, "api_sha256", values.get(3), SHA256);
        validatePattern(artifact, descriptorPath, "generator_version", values.get(4), VERSION);
        validatePattern(
                artifact, descriptorPath, "runtime_contract_version", values.get(5), VERSION);
        validatePattern(
                artifact, descriptorPath, "bridge_contract_version", values.get(6), VERSION);
        return new FoundryDescriptor(
                artifact,
                descriptorPath,
                2,
                values.get(1),
                values.get(2),
                values.get(3),
                values.get(4),
                values.get(5),
                values.get(6),
                entries);
    }

    static List<FoundryDescriptor> validateGraph(
            List<FoundryDescriptor> descriptors,
            List<AndroidPayload> payloads,
            Set<String> requestedAbis) {
        Objects.requireNonNull(descriptors, "descriptors");
        Objects.requireNonNull(payloads, "payloads");
        Objects.requireNonNull(requestedAbis, "requestedAbis");
        List<FoundryDescriptor> sorted =
                descriptors.stream()
                        .map(descriptor -> Objects.requireNonNull(descriptor, "descriptor"))
                        .sorted(
                                Comparator.comparing(FoundryDescriptor::module)
                                        .thenComparing(FoundryDescriptor::registry)
                                        .thenComparing(FoundryDescriptor::identity))
                        .toList();
        List<AndroidPayload> sortedPayloads =
                payloads.stream()
                        .map(payload -> Objects.requireNonNull(payload, "payload"))
                        .sorted(Comparator.comparing(AndroidPayload::artifact))
                        .toList();
        TreeSet<String> diagnostics = new TreeSet<>();
        validateDescriptors(sorted, diagnostics);
        validatePayloads(sortedPayloads, Set.copyOf(requestedAbis), diagnostics);
        if (!diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid Foundry dependency graph:\n" + String.join("\n", diagnostics));
        }
        return List.copyOf(sorted);
    }

    private static void validateDescriptors(
            List<FoundryDescriptor> descriptors, Set<String> diagnostics) {
        Map<String, FoundryDescriptor> modules = new HashMap<>();
        Map<String, FoundryDescriptor> registries = new HashMap<>();
        if (descriptors.isEmpty()) {
            return;
        }
        FoundryDescriptor contract = descriptors.get(0);
        for (FoundryDescriptor descriptor : descriptors) {
            duplicate(
                    "module",
                    descriptor.module(),
                    modules.putIfAbsent(descriptor.module(), descriptor),
                    descriptor,
                    diagnostics);
            duplicate(
                    "registry",
                    descriptor.registry(),
                    registries.putIfAbsent(descriptor.registry(), descriptor),
                    descriptor,
                    diagnostics);
            mixed(
                    "api_sha256",
                    contract.apiSha256(),
                    descriptor.apiSha256(),
                    contract,
                    descriptor,
                    diagnostics);
            mixed(
                    "generator_version",
                    contract.generatorVersion(),
                    descriptor.generatorVersion(),
                    contract,
                    descriptor,
                    diagnostics);
            mixed(
                    "runtime_contract_version",
                    contract.runtimeContractVersion(),
                    descriptor.runtimeContractVersion(),
                    contract,
                    descriptor,
                    diagnostics);
            mixed(
                    "bridge_contract_version",
                    contract.bridgeContractVersion(),
                    descriptor.bridgeContractVersion(),
                    contract,
                    descriptor,
                    diagnostics);
        }
    }

    private static void validatePayloads(
            List<AndroidPayload> payloads, Set<String> requestedAbis, Set<String> diagnostics) {
        List<AndroidPayload> bridges =
                payloads.stream().filter(AndroidPayload::bridgePayload).toList();
        List<AndroidPayload> configurations =
                payloads.stream().filter(AndroidPayload::configurationPayload).toList();
        if (payloads.isEmpty() && requestedAbis.isEmpty()) {
            return;
        }
        if (requestedAbis.isEmpty()) {
            for (AndroidPayload bridge : bridges) {
                diagnostics.add(
                        bridge.artifact()
                                + ": requested_abis must contain at least one Android ABI");
            }
        }
        if (bridges.size() != 1) {
            diagnostics.add("bridge payload count=" + bridges.size() + "; expected 1");
        }
        if (configurations.size() != 1) {
            diagnostics.add(
                    "configuration payload count=" + configurations.size() + "; expected 1");
        }
        if (bridges.size() != 1 || configurations.size() != 1) {
            for (AndroidPayload payload : payloads) {
                diagnostics.add(
                        payload.artifact()
                                + ": bridge_payload="
                                + payload.bridgePayload()
                                + ", configuration_payload="
                                + payload.configurationPayload());
            }
        }
        if (bridges.size() == 1
                && configurations.size() == 1
                && !bridges.get(0).artifact().equals(configurations.get(0).artifact())) {
            diagnostics.add(
                    "bridge and configuration must use the same binding artifact: "
                            + bridges.get(0).artifact()
                            + ", "
                            + configurations.get(0).artifact());
        }
        if (bridges.size() == 1) {
            AndroidPayload bridge = bridges.get(0);
            for (String abi : new TreeSet<>(requestedAbis)) {
                if (!bridge.bridgeAbis().contains(abi)) {
                    diagnostics.add(bridge.artifact() + ": missing abi=" + abi);
                }
            }
        }
    }

    private static void duplicate(
            String field,
            String value,
            FoundryDescriptor previous,
            FoundryDescriptor current,
            Set<String> diagnostics) {
        if (previous != null) {
            diagnostics.add(
                    "duplicate "
                            + field
                            + "="
                            + value
                            + ": "
                            + previous.identity()
                            + ", "
                            + current.identity());
        }
    }

    private static void mixed(
            String field,
            String expected,
            String actual,
            FoundryDescriptor first,
            FoundryDescriptor current,
            Set<String> diagnostics) {
        if (!expected.equals(actual)) {
            diagnostics.add(
                    "mixed "
                            + field
                            + ": "
                            + first.identity()
                            + " "
                            + field
                            + "="
                            + expected
                            + ", "
                            + current.identity()
                            + " "
                            + field
                            + "="
                            + actual);
        }
    }

    private static void validateHeader(
            String artifact, String path, String key, String actual, String expected) {
        if (!actual.equals(expected)) {
            throw invalid(artifact, path, key + "=" + actual + "; expected " + expected);
        }
    }

    private static void validatePattern(
            String artifact, String path, String key, String value, Pattern pattern) {
        if (!pattern.matcher(value).matches()) {
            throw invalid(artifact, path, key + "=" + value);
        }
    }

    private static void validateEntry(String artifact, String path, String kind, String value) {
        String[] parts = value.split("\\|", -1);
        if (kind.equals("class")) {
            validateClassEntry(artifact, path, kind, value, parts);
            return;
        }
        if (kind.equals("constant")) {
            validateConstantEntry(artifact, path, kind, value, parts);
            return;
        }
        if (kind.equals("property") && parts.length == 12) {
            validatePropertyEntry(artifact, path, kind, value, parts);
            return;
        }
        if (parts.length != 4) {
            throw invalid(artifact, path, kind + "=" + value);
        }
        validateMemberIdentity(artifact, path, kind, value, parts);
        boolean validSignature =
                kind.equals("property")
                        ? validJavaType(parts[3], false)
                        : validMethodSignature(parts[3], kind.equals("signal"));
        if (!validSignature) {
            throw invalid(artifact, path, kind + "=" + value);
        }
    }

    private static void validateClassEntry(
            String artifact, String path, String kind, String value, String[] parts) {
        if (parts.length != 5) {
            throw invalid(artifact, path, kind + "=" + value);
        }
        for (int index = 0; index < 4; index++) {
            if (parts[index].isBlank()) {
                throw invalid(artifact, path, kind + "=" + value);
            }
        }
        if (!QUALIFIED_JAVA_NAME.matcher(parts[0]).matches()
                || !EXPORTED_NAME.matcher(parts[1]).matches()
                || !QUALIFIED_JAVA_NAME.matcher(parts[2]).matches()
                || !INITIALIZATION_LEVELS.contains(parts[3])
                || !validDependencies(parts[4])) {
            throw invalid(artifact, path, kind + "=" + value);
        }
    }

    private static void validateConstantEntry(
            String artifact, String path, String kind, String value, String[] parts) {
        if (parts.length != 8) {
            throw invalid(artifact, path, kind + "=" + value);
        }
        validateMemberIdentity(artifact, path, kind, value, parts);
        String enumName = decodedText(parts[5]);
        boolean valid =
                parts[4].equals("d1")
                        && INTEGRAL_TYPES.contains(parts[3])
                        && enumName != null
                        && optionalText(enumName)
                        && validLong(parts[6])
                        && (parts[7].equals("0") || parts[7].equals("1"))
                        && (!parts[7].equals("1") || !enumName.isEmpty());
        if (!valid) {
            throw invalid(artifact, path, kind + "=" + value);
        }
    }

    private static void validatePropertyEntry(
            String artifact, String path, String kind, String value, String[] parts) {
        validateMemberIdentity(artifact, path, kind, value, parts);
        String getter = decodedText(parts[5]);
        String setter = decodedText(parts[6]);
        String groupName = decodedText(parts[8]);
        String groupPrefix = decodedText(parts[9]);
        String subgroupName = decodedText(parts[10]);
        String subgroupPrefix = decodedText(parts[11]);
        boolean valid =
                parts[4].equals("d1")
                        && validJavaType(parts[3], false)
                        && getter != null
                        && !getter.isBlank()
                        && setter != null
                        && optionalText(setter)
                        && validIndex(parts[7])
                        && groupName != null
                        && optionalText(groupName)
                        && groupPrefix != null
                        && optionalText(groupPrefix)
                        && subgroupName != null
                        && optionalText(subgroupName)
                        && subgroupPrefix != null
                        && optionalText(subgroupPrefix)
                        && (!groupName.isEmpty() || groupPrefix.isEmpty())
                        && (!subgroupName.isEmpty() || subgroupPrefix.isEmpty());
        if (!valid) {
            throw invalid(artifact, path, kind + "=" + value);
        }
    }

    private static void validateMemberIdentity(
            String artifact, String path, String kind, String value, String[] parts) {
        for (int index = 0; index < 4; index++) {
            if (parts[index].isBlank()) {
                throw invalid(artifact, path, kind + "=" + value);
            }
        }
        if (!QUALIFIED_JAVA_NAME.matcher(parts[0]).matches()
                || !EXPORTED_NAME.matcher(parts[1]).matches()
                || !JAVA_NAME.matcher(parts[2]).matches()) {
            throw invalid(artifact, path, kind + "=" + value);
        }
    }

    private static boolean validLong(String value) {
        if (!CANONICAL_LONG.matcher(value).matches()) {
            return false;
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    private static boolean validIndex(String value) {
        if (!CANONICAL_INDEX.matcher(value).matches()) {
            return false;
        }
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    private static boolean optionalText(String value) {
        return value.isEmpty() || !value.isBlank();
    }

    private static String decodedText(String token) {
        if (!ENCODED_TEXT.matcher(token).matches()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException failure) {
            return null;
        }
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            return null;
        }
    }

    private static boolean validDependencies(String value) {
        if (value.isEmpty()) {
            return true;
        }
        for (String dependency : value.split(",", -1)) {
            if (!QUALIFIED_JAVA_NAME.matcher(dependency).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean validMethodSignature(String value, boolean requireVoid) {
        int opening = value.indexOf('(');
        if (opening <= 0 || !value.endsWith(")") || value.indexOf('(', opening + 1) >= 0) {
            return false;
        }
        String returnType = value.substring(0, opening);
        if ((requireVoid && !returnType.equals("void")) || !validJavaType(returnType, true)) {
            return false;
        }
        String parameters = value.substring(opening + 1, value.length() - 1);
        if (parameters.isEmpty()) {
            return true;
        }
        List<String> types = splitTopLevelTypes(parameters);
        return types != null && types.stream().allMatch(type -> validJavaType(type, false));
    }

    private static boolean validJavaType(String value, boolean allowVoid) {
        if (PRIMITIVE_TYPES.contains(value)) {
            return true;
        }
        if (value.equals("void")) {
            return allowVoid;
        }
        int genericStart = value.indexOf('<');
        if (genericStart < 0) {
            return QUALIFIED_JAVA_NAME.matcher(value).matches();
        }
        if (!value.endsWith(">")
                || !QUALIFIED_JAVA_NAME.matcher(value.substring(0, genericStart)).matches()) {
            return false;
        }
        List<String> arguments =
                splitTopLevelTypes(value.substring(genericStart + 1, value.length() - 1));
        if (arguments == null || arguments.isEmpty()) {
            return false;
        }
        for (String argument : arguments) {
            String trimmed = argument.trim();
            if (trimmed.equals("?")) {
                continue;
            }
            if (trimmed.startsWith("? extends ")) {
                trimmed = trimmed.substring("? extends ".length());
            } else if (trimmed.startsWith("? super ")) {
                trimmed = trimmed.substring("? super ".length());
            }
            if (!validJavaType(trimmed, false)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> splitTopLevelTypes(String value) {
        List<String> types = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '<') {
                depth++;
            } else if (character == '>') {
                depth--;
                if (depth < 0) {
                    return null;
                }
            } else if (character == ',' && depth == 0) {
                String type = value.substring(start, index);
                if (type.isBlank()) {
                    return null;
                }
                types.add(type);
                start = index + 1;
            }
        }
        if (depth != 0) {
            return null;
        }
        String type = value.substring(start);
        if (type.isBlank()) {
            return null;
        }
        types.add(type);
        return types;
    }

    private static IllegalArgumentException invalid(String artifact, String path, String detail) {
        String identity = path.isBlank() ? artifact : artifact + "!" + path;
        return new IllegalArgumentException(
                "Invalid Foundry descriptor " + identity + ": " + detail + ".");
    }

    record AndroidPayload(
            String artifact,
            boolean bridgePayload,
            boolean configurationPayload,
            Set<String> bridgeAbis) {
        AndroidPayload {
            artifact = Objects.requireNonNull(artifact, "artifact");
            bridgeAbis = Set.copyOf(Objects.requireNonNull(bridgeAbis, "bridgeAbis"));
        }
    }
}
