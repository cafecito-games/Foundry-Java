package games.cafecito.foundry.gradle;

import java.util.ArrayList;
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
            Set.of("class", "method", "override", "property", "signal");
    private static final Pattern MODULE = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z][a-z0-9]*)*");
    private static final Pattern QUALIFIED_JAVA_NAME =
            Pattern.compile(
                    "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
                            + "(?:\\.[\\p{javaJavaIdentifierStart}]"
                            + "[\\p{javaJavaIdentifierPart}]*)+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION = Pattern.compile("[1-9][0-9]*");

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
        int requiredParts = kind.equals("class") ? 5 : 4;
        if (parts.length != requiredParts) {
            throw invalid(artifact, path, kind + "=" + value);
        }
        int requiredValues = kind.equals("class") ? 4 : 4;
        for (int index = 0; index < requiredValues; index++) {
            if (parts[index].isBlank()) {
                throw invalid(artifact, path, kind + "=" + value);
            }
        }
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
