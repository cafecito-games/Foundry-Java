package games.cafecito.foundry.api.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** A verified accepted Foundry API JSON and public FoundryExtension interface header. */
public record ApiInputs(
        ApiProvenance provenance,
        String extensionApiJson,
        String interfaceHeader,
        String compatibilityManifestJson) {
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> PROVENANCE_KEYS =
            Set.of(
                    "schema_version",
                    "source_repository",
                    "source_release",
                    "release_url",
                    "archive_url",
                    "archive_sha256",
                    "foundry_commit",
                    "foundry_version",
                    "api_version",
                    "abi_minimum",
                    "source_license",
                    "source_license_url",
                    "generator_version",
                    "bridge_contract_version",
                    "files");
    private static final Set<String> INPUT_KEYS = Set.of("path", "sha256");

    public static ApiInputs load(Path apiDirectory) {
        Path provenancePath = apiDirectory.resolve("provenance.json");
        String provenanceText = readRegularFile(provenancePath, "provenance.json");
        JsonValue.JsonObject root = JsonParser.parse(provenanceText).requireObject("$");
        requireExactKeys(root, PROVENANCE_KEYS, "$");

        int schemaVersion = root.require("schema_version", "$").requireInt("$.schema_version");
        if (schemaVersion != 1) {
            throw new ApiInputException("$.schema_version must be 1.");
        }

        String commit = requiredString(root, "foundry_commit", "$");
        requireIdentity(COMMIT, commit, "$.foundry_commit", "40 lowercase hexadecimal");
        String archiveHash = requiredString(root, "archive_sha256", "$");
        requireIdentity(SHA256, archiveHash, "$.archive_sha256", "64 lowercase hexadecimal");

        JsonValue.JsonObject files = root.require("files", "$").requireObject("$.files");
        requireExactKeys(
                files,
                Set.of(
                        "extension_api_json",
                        "foundry_extension_interface_h",
                        "compatibility_manifest_json"),
                "$.files");
        Map<String, ApiProvenance.InputFile> inputFiles =
                Map.of(
                        "extension_api_json",
                        parseInput(files, "extension_api_json"),
                        "foundry_extension_interface_h",
                        parseInput(files, "foundry_extension_interface_h"),
                        "compatibility_manifest_json",
                        parseInput(files, "compatibility_manifest_json"));

        ApiProvenance provenance =
                new ApiProvenance(
                        schemaVersion,
                        requiredString(root, "source_repository", "$"),
                        requiredString(root, "source_release", "$"),
                        requiredString(root, "release_url", "$"),
                        requiredString(root, "archive_url", "$"),
                        archiveHash,
                        commit,
                        requiredString(root, "foundry_version", "$"),
                        requiredString(root, "api_version", "$"),
                        requiredString(root, "abi_minimum", "$"),
                        requiredString(root, "source_license", "$"),
                        requiredString(root, "source_license_url", "$"),
                        requiredString(root, "generator_version", "$"),
                        requiredString(root, "bridge_contract_version", "$"),
                        inputFiles);

        String api = readAndVerify(apiDirectory, "extension_api_json", inputFiles);
        String header = readAndVerify(apiDirectory, "foundry_extension_interface_h", inputFiles);
        String manifest = readAndVerify(apiDirectory, "compatibility_manifest_json", inputFiles);
        return new ApiInputs(provenance, api, header, manifest);
    }

    public String extensionApiSha256() {
        return provenance.files().get("extension_api_json").sha256();
    }

    public String interfaceHeaderSha256() {
        return provenance.files().get("foundry_extension_interface_h").sha256();
    }

    public String compatibilityManifestSha256() {
        return provenance.files().get("compatibility_manifest_json").sha256();
    }

    private static ApiProvenance.InputFile parseInput(
            JsonValue.JsonObject files, String inputName) {
        String path = "$.files." + inputName;
        JsonValue.JsonObject input = files.require(inputName, "$.files").requireObject(path);
        requireExactKeys(input, INPUT_KEYS, path);
        String relativePath = requiredString(input, "path", path);
        if (relativePath.isBlank()
                || relativePath.contains("/")
                || relativePath.contains("\\")
                || relativePath.equals(".")
                || relativePath.equals("..")) {
            throw new ApiInputException(path + ".path must be a plain relative file name.");
        }
        String hash = requiredString(input, "sha256", path);
        requireIdentity(SHA256, hash, path + ".sha256", "64 lowercase hexadecimal");
        return new ApiProvenance.InputFile(relativePath, hash);
    }

    private static String readAndVerify(
            Path apiDirectory, String inputName, Map<String, ApiProvenance.InputFile> inputFiles) {
        ApiProvenance.InputFile input = inputFiles.get(inputName);
        String contents = readRegularFile(apiDirectory.resolve(input.path()), input.path());
        String actual = sha256(contents.getBytes(StandardCharsets.UTF_8));
        if (!actual.equals(input.sha256())) {
            throw new ApiInputException(
                    "$.files."
                            + inputName
                            + ".sha256 does not match "
                            + input.path()
                            + ": expected "
                            + input.sha256()
                            + ", got "
                            + actual
                            + ".");
        }
        return contents;
    }

    private static String readRegularFile(Path path, String identity) {
        if (!Files.isRegularFile(path)) {
            throw new ApiInputException(identity + " must exist as a regular file.");
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ApiInputException("Could not read " + identity + ".", exception);
        }
    }

    private static String requiredString(
            JsonValue.JsonObject object, String key, String parentPath) {
        String path = parentPath + "." + key;
        String value = object.require(key, parentPath).requireString(path);
        if (value.isBlank()) {
            throw new ApiInputException(path + " must not be blank.");
        }
        return value;
    }

    private static void requireExactKeys(
            JsonValue.JsonObject object, Set<String> accepted, String path) {
        for (String key : object.values().keySet()) {
            if (!accepted.contains(key)) {
                throw new ApiInputException("Unknown construct at " + path + "." + key + ".");
            }
        }
        for (String key : accepted) {
            if (!object.values().containsKey(key)) {
                throw new ApiInputException(path + "." + key + " is required.");
            }
        }
    }

    private static void requireIdentity(
            Pattern pattern, String value, String path, String description) {
        if (!pattern.matcher(value).matches()) {
            throw new ApiInputException(path + " must contain exactly " + description + " digits.");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java runtime provides SHA-256.", exception);
        }
    }
}
