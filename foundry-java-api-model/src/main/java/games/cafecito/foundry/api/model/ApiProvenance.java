package games.cafecito.foundry.api.model;

import java.util.Map;

/** Immutable provenance for one accepted Foundry API input set. */
public record ApiProvenance(
        int schemaVersion,
        String sourceRepository,
        String sourceRelease,
        String releaseUrl,
        String archiveUrl,
        String archiveSha256,
        String foundryCommit,
        String foundryVersion,
        String apiVersion,
        String abiMinimum,
        String license,
        String licenseUrl,
        String generatorVersion,
        String bridgeContractVersion,
        Map<String, InputFile> files) {
    public ApiProvenance {
        files = Map.copyOf(files);
    }

    public record InputFile(String path, String sha256) {}
}
