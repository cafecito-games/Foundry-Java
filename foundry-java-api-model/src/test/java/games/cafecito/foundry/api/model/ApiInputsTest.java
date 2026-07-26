package games.cafecito.foundry.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiInputsTest {
    private static final String COMMIT = "3923e920b2fb6db68f82dfdab2bf7b1df125492d";
    private static final String ARCHIVE_HASH =
            "5e8dd7cea34051297c2ca89ec05fc2d50ee921b156d220b6007cc274d769beec";

    @TempDir Path temporaryDirectory;

    @Test
    void loadsTheCheckedInAcceptedInputs() {
        Path acceptedDirectory =
                Path.of(System.getProperty("user.dir")).resolve("../api/current").normalize();

        ApiInputs inputs = ApiInputs.load(acceptedDirectory);

        assertEquals(COMMIT, inputs.provenance().foundryCommit());
        assertEquals(
                "85e91174c1a8a48629223d6459bb2ef595ad1da405b2ce88435c24fe221aec51",
                inputs.extensionApiSha256());
        assertEquals(
                "ecf9a1f1e6b2642385a521725313efb2baea8b81fcac9dc837f55a4b90498991",
                inputs.interfaceHeaderSha256());
    }

    @Test
    void loadsExactImmutableReleaseProvenanceAndVerifiesInputs() throws IOException {
        Path apiDirectory = writeAcceptedInputs();

        ApiInputs inputs = ApiInputs.load(apiDirectory);

        assertEquals(COMMIT, inputs.provenance().foundryCommit());
        assertEquals("0.1.0-alpha.8", inputs.provenance().foundryVersion());
        assertEquals("0.1.0-alpha.8", inputs.provenance().apiVersion());
        assertEquals("0.1.0", inputs.provenance().abiMinimum());
        assertEquals("1", inputs.provenance().generatorVersion());
        assertEquals("1", inputs.provenance().bridgeContractVersion());
        assertEquals(
                "https://github.com/cafecito-games/Foundry/releases/tag/v0.1.0-alpha.8",
                inputs.provenance().releaseUrl());
        assertEquals("MIT", inputs.provenance().license());
        assertEquals("{\"header\":{}}\n", inputs.extensionApiJson());
        assertEquals("/* public FoundryExtension ABI */\n", inputs.interfaceHeader());
        assertEquals("{\"entries\":[]}\n", inputs.compatibilityManifestJson());
    }

    @Test
    void rejectsMissingRequiredIdentityWithJsonPath() throws IOException {
        Path apiDirectory = writeAcceptedInputs();
        Path provenance = apiDirectory.resolve("provenance.json");
        Files.writeString(
                provenance,
                Files.readString(provenance)
                        .replace("  \"foundry_commit\": \"" + COMMIT + "\",\n", ""),
                StandardCharsets.UTF_8);

        ApiInputException failure =
                assertThrows(ApiInputException.class, () -> ApiInputs.load(apiDirectory));

        assertTrue(failure.getMessage().contains("$.foundry_commit"));
    }

    @Test
    void rejectsMalformedCommitAndHashes() throws IOException {
        Path apiDirectory = writeAcceptedInputs();
        Path provenance = apiDirectory.resolve("provenance.json");
        Files.writeString(
                provenance,
                Files.readString(provenance).replace(COMMIT, "not-a-commit"),
                StandardCharsets.UTF_8);

        ApiInputException failure =
                assertThrows(ApiInputException.class, () -> ApiInputs.load(apiDirectory));

        assertTrue(failure.getMessage().contains("$.foundry_commit"));
        assertTrue(failure.getMessage().contains("40 lowercase hexadecimal"));
    }

    @Test
    void rejectsAHashMismatchBeforeReturningApiText() throws IOException {
        Path apiDirectory = writeAcceptedInputs();
        Files.writeString(
                apiDirectory.resolve("extension_api.json"),
                "{\"header\":{\"changed\":true}}\n",
                StandardCharsets.UTF_8);

        ApiInputException failure =
                assertThrows(ApiInputException.class, () -> ApiInputs.load(apiDirectory));

        assertTrue(failure.getMessage().contains("$.files.extension_api_json.sha256"));
        assertTrue(failure.getMessage().contains("does not match"));
    }

    @Test
    void rejectsMissingInputFile() throws IOException {
        Path apiDirectory = writeAcceptedInputs();
        Files.delete(apiDirectory.resolve("foundry_extension_interface.h"));

        ApiInputException failure =
                assertThrows(ApiInputException.class, () -> ApiInputs.load(apiDirectory));

        assertTrue(failure.getMessage().contains("foundry_extension_interface.h"));
        assertTrue(failure.getMessage().contains("regular file"));
    }

    @Test
    void rejectsCompatibilityManifestHashMismatch() throws IOException {
        Path apiDirectory = writeAcceptedInputs();
        Files.writeString(
                apiDirectory.resolve("compatibility-manifest.json"),
                "{\"entries\":[{\"unexpected\":true}]}\n",
                StandardCharsets.UTF_8);

        ApiInputException failure =
                assertThrows(ApiInputException.class, () -> ApiInputs.load(apiDirectory));

        assertTrue(failure.getMessage().contains("$.files.compatibility_manifest_json.sha256"));
        assertTrue(failure.getMessage().contains("does not match"));
    }

    private Path writeAcceptedInputs() throws IOException {
        Path apiDirectory = temporaryDirectory.resolve("current");
        Files.createDirectories(apiDirectory);
        String api = "{\"header\":{}}\n";
        String header = "/* public FoundryExtension ABI */\n";
        String manifest = "{\"entries\":[]}\n";
        Files.writeString(apiDirectory.resolve("extension_api.json"), api, StandardCharsets.UTF_8);
        Files.writeString(
                apiDirectory.resolve("foundry_extension_interface.h"),
                header,
                StandardCharsets.UTF_8);
        Files.writeString(
                apiDirectory.resolve("compatibility-manifest.json"),
                manifest,
                StandardCharsets.UTF_8);
        Files.writeString(
                apiDirectory.resolve("provenance.json"),
                provenanceJson(sha256(api), sha256(header), sha256(manifest)),
                StandardCharsets.UTF_8);
        return apiDirectory;
    }

    private static String provenanceJson(String apiHash, String headerHash, String manifestHash) {
        return """
                {
                  "schema_version": 1,
                  "source_repository": "https://github.com/cafecito-games/Foundry",
                  "source_release": "v0.1.0-alpha.8",
                  "release_url": "https://github.com/cafecito-games/Foundry/releases/tag/v0.1.0-alpha.8",
                  "archive_url": "https://github.com/cafecito-games/Foundry/releases/download/v0.1.0-alpha.8/Foundry_v0.1.0-alpha.8_api.zip",
                  "archive_sha256": "%s",
                  "foundry_commit": "%s",
                  "foundry_version": "0.1.0-alpha.8",
                  "api_version": "0.1.0-alpha.8",
                  "abi_minimum": "0.1.0",
                  "source_license": "MIT",
                  "source_license_url": "https://github.com/cafecito-games/Foundry/blob/3923e920b2fb6db68f82dfdab2bf7b1df125492d/LICENSE.txt",
                  "generator_version": "1",
                  "bridge_contract_version": "1",
                  "files": {
                    "extension_api_json": {
                      "path": "extension_api.json",
                      "sha256": "%s"
                    },
                    "foundry_extension_interface_h": {
                      "path": "foundry_extension_interface.h",
                      "sha256": "%s"
                    },
                    "compatibility_manifest_json": {
                      "path": "compatibility-manifest.json",
                      "sha256": "%s"
                    }
                  }
                }
                """
                .formatted(ARCHIVE_HASH, COMMIT, apiHash, headerHash, manifestHash);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
