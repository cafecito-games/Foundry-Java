package games.cafecito.foundry.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Contract for the tag-driven release pipeline.
 *
 * <p>The legs that need real credentials — an upload to Maven Central, a Central Portal token, a
 * production signing key — cannot run here. This test therefore owns everything about the pipeline
 * that is verifiable from the repository itself: that publication is tag-driven and ordered
 * strictly after the complete gate set, that every refusal is implemented, that signing and
 * verification precede any upload, that secrets are scoped to the release workflow and never
 * echoed, and that the documented process matches the automation. {@link
 * ReleaseScriptBehaviourTest} executes the refusals and the staged-repository verifier for real.
 */
class ReleasePipelineContractTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final String WORKFLOW = ".github/workflows/release.yml";
    private static final String PRECONDITIONS = "gradle/verify-release-preconditions.sh";
    private static final String STAGE = "gradle/stage-release.sh";
    private static final String VERIFY_STAGED = "gradle/verify-staged-release.sh";
    private static final String REPRODUCIBILITY = "gradle/verify-release-reproducibility.sh";
    private static final String UPLOAD = "gradle/upload-staged-release.sh";
    private static final String DRY_RUN = "gradle/run-release-staging-dry-run.sh";
    private static final List<String> RELEASE_SCRIPTS =
            List.of(PRECONDITIONS, STAGE, VERIFY_STAGED, REPRODUCIBILITY, UPLOAD, DRY_RUN);
    private static final List<String> PUBLISHED_MODULES =
            List.of(
                    "foundry-java-android",
                    "foundry-java-annotations",
                    "foundry-java-api-model",
                    "foundry-java-generator",
                    "foundry-java-gradle-plugin",
                    "foundry-java-kotlin",
                    "foundry-java-processor",
                    "foundry-java-runtime",
                    "foundry-java-test");
    private static final List<String> GATE_STEPS =
            List.of(
                    "bash gradle/verify-configuration-cache-reuse.sh",
                    "bash gradle/verify-native-bridge.sh",
                    "bash gradle/run-samples-conformance-matrix.sh",
                    "bash gradle/run-engine-loaded-conformance-gate.sh",
                    ":foundry-java-android:nativeHostTest",
                    ":foundry-java-android:nativeSanitizerTest",
                    ":foundry-java-android:nativeAbiLayoutTest",
                    ":foundry-java-runtime:verifyGeneratedRealization",
                    ":foundry-java-runtime:verifyRuntimeApi");
    private static final List<String> SECRET_NAMES =
            List.of(
                    "FOUNDRY_SIGNING_KEY",
                    "FOUNDRY_SIGNING_PASSWORD",
                    "FOUNDRY_CENTRAL_PORTAL_TOKEN");

    @Test
    void everyReleaseScriptIsAnExecutableStrictBashProgram() throws IOException {
        for (String script : RELEASE_SCRIPTS) {
            Path path = ROOT.resolve(script);
            assertTrue(Files.isRegularFile(path), script + " must exist");
            assertTrue(Files.isExecutable(path), script + " must be executable");
            String text = Files.readString(path);
            assertTrue(
                    text.startsWith("#!/usr/bin/env bash\n"), script + " must be a bash program");
            assertTrue(text.contains("set -euo pipefail"), script + " must fail fast");
            assertFalse(text.contains("|| true"), script + " must not swallow failures");
            // A logical path leaves symlinked parents unresolved, so a path that must be compared
            // against the checkout, or deleted, is always resolved physically.
            assertFalse(text.contains("&& pwd)"), script + " must resolve paths with pwd -P");
        }
    }

    @Test
    void publicationIsTagDrivenAndOrderedAfterTheCompleteGateSet() throws IOException {
        String workflow = read(WORKFLOW);

        assertTrue(workflow.contains("  push:\n    tags:\n      - 'v*'"));
        assertFalse(
                workflow.contains("pull_request"), "a release must never run for a pull request");
        assertTrue(workflow.contains("concurrency:"));

        // Only `check` is a required status context on this repository, and require-branches-up-to-
        // date is off, so branch protection cannot be the thing that orders publication after the
        // gates. The release workflow runs the complete gate set itself.
        for (String gateStep : GATE_STEPS) {
            assertTrue(workflow.contains(gateStep), gateStep + " must run before publication");
        }
        assertTrue(workflow.contains("./gradlew --no-daemon --write-locks resolveAndLockAll"));

        int hostGate = workflow.indexOf("  host-gate:\n");
        int deviceGate = workflow.indexOf("  device-gate:\n");
        int stage = workflow.indexOf("  stage:\n");
        int publish = workflow.indexOf("  publish:\n");
        assertTrue(hostGate > 0 && deviceGate > 0 && stage > 0 && publish > 0);
        assertTrue(stage > deviceGate && stage > hostGate, "staging follows both gate jobs");
        assertTrue(publish > stage, "publication follows staging");
        assertTrue(workflow.contains("needs: [host-gate, device-gate]"));
        assertTrue(workflow.contains("needs: [stage]"));

        // The engine-loaded gate downloads a roughly 1.1 GB export template and builds five
        // exports, so the device gate keeps the same budget the pull-request gate uses.
        assertTrue(workflow.contains("timeout-minutes: 150"));
        assertTrue(workflow.contains("FOUNDRY_ENGINE_CACHE"));

        // No publication task may appear in any gate job.
        String gateSection = workflow.substring(hostGate, stage);
        assertFalse(gateSection.contains("upload-staged-release"));
        assertFalse(gateSection.contains("stage-release.sh"));
    }

    @Test
    void aReleaseRefusesAMismatchedTagDirtyLocksOrAnUncleanWorkingTree() throws IOException {
        String preconditions = read(PRECONDITIONS);

        assertTrue(preconditions.contains("does not match the declared project version"));
        assertTrue(preconditions.contains("dependency lock"));
        assertTrue(preconditions.contains("working tree is not clean"));
        assertTrue(preconditions.contains("is not a release version"));
        assertTrue(preconditions.contains("does not point at HEAD"));
        assertTrue(preconditions.contains("SNAPSHOT"));
        assertTrue(preconditions.contains("gradle.properties"));
        assertTrue(preconditions.contains("foundryVersion"));
        assertTrue(
                preconditions.contains("status --porcelain --untracked-files=all"),
                "untracked files must count as an unclean tree");
        assertTrue(
                preconditions.contains("--write-locks resolveAndLockAll"),
                "the strict path must regenerate the locks and require them unchanged");
        // Pointing at HEAD is not enough on its own: a tag on an unmerged branch would satisfy it.
        assertTrue(preconditions.contains("merge-base --is-ancestor"));
        assertTrue(preconditions.contains("refs/remotes/origin/main"));
        assertTrue(preconditions.contains("is not contained in"));

        // The declared project version is checked in, so a tag can genuinely disagree with it.
        String properties = read("gradle.properties");
        Matcher declared = Pattern.compile("(?m)^foundryVersion=(\\S+)$").matcher(properties);
        assertTrue(declared.find(), "gradle.properties must declare foundryVersion");
        assertFalse(declared.group(1).contains("SNAPSHOT"), "the declared version is a release");

        // Every refusal precedes any signing or publishing work.
        String stage = read(STAGE);
        int preconditionCall = stage.indexOf("verify-release-preconditions.sh");
        int publishCall = stage.indexOf("publishAllPublicationsToStagingRepository");
        assertTrue(preconditionCall > 0 && publishCall > preconditionCall);
    }

    @Test
    void stagingTheSameTagTwiceMustProduceByteIdenticalArtifacts() throws IOException {
        String reproducibility = read(REPRODUCIBILITY);
        String rootBuild = read("build.gradle.kts");

        assertTrue(rootBuild.contains("isPreserveFileTimestamps = false"));
        assertTrue(rootBuild.contains("isReproducibleFileOrder = true"));
        // Javadoc writes its generation date into every page unless it is told not to, which would
        // make the javadoc archive differ between two runs on different days.
        assertTrue(rootBuild.contains("addBooleanOption(\"notimestamp\", true)"));

        assertTrue(reproducibility.contains("stage-release.sh"));
        assertEquals(
                2,
                occurrences(reproducibility, "bash \"${repo_root}/gradle/stage-release.sh\""),
                "reproducibility is proven by staging the same tag twice");
        assertTrue(reproducibility.contains("cmp -s"));
        // Two stagings are only independent if neither can restore the other's outputs from the
        // local
        // build cache.
        assertTrue(read(STAGE).contains("--no-build-cache"));
        assertTrue(reproducibility.contains("is not reproducible"));
        // An OpenPGP signature embeds its own creation time, so the signatures cannot be compared
        // byte for byte. Everything they sign can be, and is.
        assertTrue(reproducibility.contains("*.asc"));
        assertTrue(reproducibility.contains("signature creation time"));
        assertTrue(reproducibility.contains("summary.json"));
    }

    @Test
    void signaturesAndChecksumsAreVerifiedAgainstTheStagedRepositoryBeforeAnyUpload()
            throws IOException {
        String verify = read(VERIFY_STAGED);
        String upload = read(UPLOAD);
        String workflow = read(WORKFLOW);

        assertTrue(verify.contains("gpg --batch --quiet --status-fd 1"));
        assertTrue(verify.contains("--verify \"${file}.asc\" \"$file\""));
        assertTrue(verify.contains("is not signed"));
        assertTrue(verify.contains("signature is invalid"));
        for (String algorithm : List.of("md5", "sha1", "sha256", "sha512")) {
            assertTrue(verify.contains(algorithm), algorithm + " must be verified");
        }
        assertTrue(verify.contains("checksum"));
        assertTrue(verify.contains("verification-summary.json"));

        // Verification is anchored to an expected key rather than to the key the signer supplied,
        // so
        // a valid signature from an unintended key is rejected.
        assertTrue(verify.contains("FOUNDRY_RELEASE_SIGNING_FINGERPRINT"));
        assertTrue(verify.contains("VALIDSIG"));
        assertTrue(verify.contains("does not match the expected release key"));
        assertTrue(verify.contains("not the expected release key"));
        assertTrue(
                workflow.contains(
                        "FOUNDRY_RELEASE_SIGNING_FINGERPRINT:"
                                + " ${{ vars.FOUNDRY_SIGNING_KEY_FINGERPRINT }}"));

        // Ordering is structural, not conventional: the uploader refuses to run against a staged
        // repository that carries no verification summary.
        assertTrue(upload.contains("verification-summary.json"));
        assertTrue(upload.contains("has not been verified"));

        int verifyStep = workflow.indexOf("bash gradle/verify-staged-release.sh");
        int uploadStep = workflow.indexOf("bash gradle/upload-staged-release.sh");
        assertTrue(verifyStep > 0 && uploadStep > verifyStep, "verification precedes upload");
    }

    @Test
    void everyPublishedModuleShipsSourcesAndJavadocWithValidatedMetadata() throws IOException {
        String rootBuild = read("build.gradle.kts");
        String androidBuild = read("foundry-java-android/build.gradle.kts");
        String verify = read(VERIFY_STAGED);

        assertTrue(rootBuild.contains("withSourcesJar()"));
        assertTrue(rootBuild.contains("withJavadocJar()"));
        assertTrue(androidBuild.contains("withSourcesJar()"));
        assertTrue(androidBuild.contains("withJavadocJar()"));

        // The bootstrap topology is 10 POMs, 9 Gradle modules, and 8 main JARs plus 1 AAR. Sources
        // and Javadoc are counted separately so the main-artifact topology stays an exact check.
        assertTrue(rootBuild.contains("check(poms.size == 10)"));
        assertTrue(rootBuild.contains("check(modules.size == 9)"));
        assertTrue(rootBuild.contains("check(jarCount == 8 && aarCount == 1)"));
        assertTrue(rootBuild.contains("check(sourcesJarCount == 9 && javadocJarCount == 9)"));
        assertEquals(9, PUBLISHED_MODULES.size());
        for (String module : PUBLISHED_MODULES) {
            assertTrue(rootBuild.contains(module + "|jar|sources|"), module + " sources");
            assertTrue(rootBuild.contains(module + "|jar|javadoc|"), module + " javadoc");
        }

        // The declared topology the staged-release verifier enforces and the publication topology
        // the
        // build configures are the same topology, so they are compared here and cannot drift.
        List<String> topology =
                read("gradle/release-topology.txt")
                        .lines()
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .toList();
        assertEquals(10, topology.size(), "ten coordinates: nine modules and the plugin marker");
        for (String coordinate : topology) {
            String[] fields = coordinate.split(":");
            assertEquals(4, fields.length, coordinate);
            assertTrue(rootBuild.contains(fields[1]), fields[1] + " must be a published module");
            assertTrue(List.of("jar", "aar", "pom").contains(fields[2]), coordinate + " packaging");
        }
        assertTrue(
                topology.contains(
                        "games.cafecito.foundry:foundry-java-runtime:jar:sourcesElements+javadocElements"));
        // The Android library plugin's own Javadoc generation cannot read Java records in a
        // dependency, so that Javadoc is a Maven artifact rather than a Gradle variant.
        assertTrue(
                topology.contains(
                        "games.cafecito.foundry:foundry-java-android:aar"
                                + ":releaseVariantReleaseSourcePublication"));
        assertTrue(androidBuild.contains("releaseJavadocJar"));
        assertTrue(
                topology.contains(
                        "games.cafecito.foundry.java:games.cafecito.foundry.java.gradle.plugin:pom:none"));

        // Maven Central rejects a POM without these, so they are part of the published contract.
        assertTrue(rootBuild.contains("developers {"));
        assertTrue(verify.contains("developers"));
        assertTrue(verify.contains("licenses"));
        assertTrue(verify.contains("scm"));
        assertTrue(verify.contains("-sources.jar"));
        assertTrue(verify.contains("-javadoc.jar"));
        // The Gradle module variants each coordinate must publish are declared in the topology and
        // enforced from it, so the verifier cannot drift from the published set.
        assertTrue(verify.contains("declared_variants"));
        assertTrue(read("gradle/release-topology.txt").contains("sourcesElements"));
        assertTrue(read("gradle/release-topology.txt").contains("javadocElements"));
    }

    @Test
    void releaseProvenanceRecordsTheCommitEngineIdentityAndSurfaceManifest() throws IOException {
        String stage = read(STAGE);

        assertTrue(stage.contains("release-provenance.json"));
        for (String field :
                List.of(
                        "source_commit",
                        "release_tag",
                        "binding_version",
                        "engine_api_version",
                        "engine_api_sha256",
                        "engine_producer_commit",
                        "generator_version",
                        "bridge_contract_version",
                        "surface_manifest_sha256",
                        "artifacts")) {
            assertTrue(stage.contains(field), field + " must be recorded");
        }
        // The surface manifest is a build output, not a checked-in artifact, so provenance copies
        // the produced file rather than referring to a path in the source tree.
        assertTrue(
                stage.contains(
                        "foundry-java-runtime/build/generated/foundryApi/"
                                + "foundry-java-surface-manifest.json"));
        assertTrue(stage.contains("foundry-java-surface-manifest.json\""));
        assertTrue(stage.contains("api/current/provenance.json"));
    }

    @Test
    void releaseCredentialsAreScopedEncryptedSecretsThatAreNeverEchoed() throws IOException {
        String workflow = read(WORKFLOW);

        // Secrets reach the pipeline only through environment-scoped GitHub secrets, and only in
        // the jobs that need them. A pull-request workflow can never request them because this
        // workflow has no pull-request trigger at all.
        assertTrue(workflow.contains("environment: release-signing"));
        assertTrue(workflow.contains("environment: maven-central"));
        for (String secret : SECRET_NAMES) {
            assertTrue(workflow.contains(secret + ": ${{ secrets." + secret + " }}"), secret);
        }
        for (String secret : SECRET_NAMES) {
            assertFalse(workflow.contains("echo \"${" + secret), secret + " must never be echoed");
            assertFalse(workflow.contains("echo ${{ secrets." + secret), secret);
            assertFalse(workflow.contains("printf '%s' \"${" + secret), secret);
        }
        assertFalse(
                workflow.contains("::add-mask::"), "masking is not a substitute for not printing");
        assertFalse(workflow.contains("set -x"), "tracing would expose secret arguments");
        assertTrue(workflow.contains("permissions:\n  contents: read"));

        // No release script may put secret material on a command line, in a log, or into a file.
        for (String script : RELEASE_SCRIPTS) {
            String text = read(script);
            for (String secret : SECRET_NAMES) {
                assertFalse(text.contains("echo \"$" + secret), script + " " + secret);
                assertFalse(text.contains("echo \"${" + secret), script + " " + secret);
                assertFalse(text.contains("printf '%s' \"$" + secret), script + " " + secret);
                assertFalse(text.contains("--password " + secret), script + " " + secret);
            }
            assertFalse(text.contains("set -x"), script + " must not trace");
        }
        assertTrue(read(UPLOAD).contains("--config -"), "the token is fed to curl on stdin");
        assertTrue(read(STAGE).contains("ORG_GRADLE_PROJECT_signingKey"));
        assertFalse(read(STAGE).contains("-Psigning"), "signing material never becomes a -P value");

        // Signing is configured from a Gradle property supplied by that environment value, and it
        // is
        // only applied when key material is actually present, so an ordinary `check` configures no
        // signing at all.
        String rootBuild = read("build.gradle.kts");
        assertTrue(rootBuild.contains("useInMemoryPgpKeys"));
        assertTrue(rootBuild.contains("providers.gradleProperty(\"signingKey\")"));
        assertTrue(rootBuild.contains("if (releaseSigningKey.isPresent)"));
        assertFalse(rootBuild.contains("signing.password"), "no signing material is checked in");
    }

    @Test
    void theStagingDryRunExercisesSigningValidationAndUploadWithoutMavenCentral()
            throws IOException {
        String dryRun = read(DRY_RUN);
        String workflow = read(WORKFLOW);

        assertTrue(dryRun.contains("verify-release-reproducibility.sh"));
        assertTrue(dryRun.contains("verify-staged-release.sh"));
        assertTrue(dryRun.contains("upload-staged-release.sh"));
        assertTrue(
                dryRun.contains("--quick-generate-key"), "an ephemeral signing key is generated");
        assertTrue(dryRun.contains("GNUPGHOME"));
        assertTrue(dryRun.contains("staging"));
        assertTrue(
                dryRun.contains("must never publish to Maven Central"),
                "the dry run refuses the Central target outright");
        assertFalse(dryRun.contains("central.sonatype.com"));
        assertFalse(dryRun.contains("repo1.maven.org"));
        // Uploading twice is the idempotence proof, so the second upload must be required to fail.
        assertTrue(dryRun.contains("unexpectedly succeeded"));
        assertTrue(dryRun.contains("summary.json"));

        assertTrue(workflow.contains("bash gradle/run-release-staging-dry-run.sh"));
        assertTrue(workflow.contains("dry_run"));
        // A dispatch that opts out of the dry run cannot release anything, so it is refused rather
        // than reported as a successful run that did nothing.
        assertTrue(
                workflow.contains(
                        "if: github.event_name == 'workflow_dispatch' && !inputs.dry_run"));
        assertTrue(workflow.contains("A real release is a tag push."));
    }

    @Test
    void publicationIsIdempotentAndFailsLoudlyOnAnAlreadyPublishedCoordinate() throws IOException {
        String upload = read(UPLOAD);

        assertTrue(upload.contains("is already published"));
        assertTrue(upload.contains("refusing to republish"));
        // The published-coordinate check covers every coordinate before a single byte is uploaded,
        // so a partially republished release is not reachable.
        int coordinateCheck = upload.indexOf("already published");
        int transfer = upload.indexOf("upload_bundle");
        assertTrue(coordinateCheck > 0 && transfer > coordinateCheck);
        assertTrue(upload.contains("central"));
        assertTrue(upload.contains("staging"));
        assertTrue(upload.contains("repo1.maven.org"), "the Central release repository is queried");
        // Only a 404 proves a coordinate is free; an inconclusive answer stops the release.
        assertTrue(upload.contains("404) ;;"));
        assertTrue(upload.contains("is published is unknown"));
        assertTrue(upload.contains("central.sonatype.com"));
        assertTrue(upload.contains("FOUNDRY_CENTRAL_PORTAL_TOKEN"));
        assertTrue(
                upload.contains("upload-summary.json"),
                "a completed upload records what it uploaded so a rerun is a no-op decision");
        // A Central Portal deployment stays USER_MANAGED and is therefore invisible to the public
        // coordinate probe, so the recorded upload is what stops a retry submitting a second
        // bundle.
        assertTrue(upload.contains("deployment_id"));
        assertTrue(upload.contains("USER_MANAGED"));
        // The intent marker is written before the irreversible call, so it survives a crash between
        // the upload and the completed record being written, and recovery treats it as ambiguous
        // rather than as permission to retry.
        assertTrue(upload.contains("upload-intent.json"));
        int intentWrite = upload.indexOf("\"result\": \"intent\"");
        int uploadBundleCall = upload.lastIndexOf("upload_bundle");
        assertTrue(intentWrite > 0 && uploadBundleCall > intentWrite);
        assertTrue(upload.contains("ambiguous"));

        // The record has to survive the runner, or a re-run of the publish job would download the
        // original staged release, see no record, and submit the bundle a second time.
        String workflow = read(WORKFLOW);
        assertTrue(
                workflow.contains(
                        "name: foundry-java-release-upload-record-${{ github.ref_name }}"));
        assertTrue(
                workflow.contains(
                        "name: foundry-java-release-upload-intent-${{ github.ref_name }}"),
                "the intent marker must be its own artifact, distinct from the completed record");
        // The intent artifact must never be overwritten: an overwrite is a delete followed by an
        // upload, and if the runner is lost between the two, the artifact is gone entirely, leaving
        // no
        // durable evidence at all that an attempt happened.
        int intentArtifactUpload =
                workflow.indexOf("name: foundry-java-release-upload-intent-${{ github.ref_name }}");
        int recordArtifactUpload =
                workflow.indexOf("name: foundry-java-release-upload-record-${{ github.ref_name }}");
        String intentArtifactStep =
                workflow.substring(intentArtifactUpload, intentArtifactUpload + 200);
        assertFalse(intentArtifactStep.contains("overwrite"));
        String recordArtifactStep =
                workflow.substring(recordArtifactUpload, recordArtifactUpload + 200);
        assertTrue(recordArtifactStep.contains("overwrite: true"));
        // Recovery must fail closed: only an empty artifact listing may be read as "not uploaded".
        assertFalse(workflow.contains("continue-on-error"));
        // Dedup is scoped by the release version's artifact name across the whole repository, not
        // by
        // GITHUB_RUN_ID, so a fresh tag-push run can see a still-unpublished deployment left by a
        // different, earlier run.
        assertFalse(workflow.contains("actions/runs/${GITHUB_RUN_ID}/artifacts"));
        assertTrue(workflow.contains("repos/${GITHUB_REPOSITORY}/actions/artifacts"));
        assertTrue(
                workflow.contains(
                        "record_name=\"foundry-java-release-upload-record-${GITHUB_REF_NAME}\""));
        assertTrue(
                workflow.contains(
                        "intent_name=\"foundry-java-release-upload-intent-${GITHUB_REF_NAME}\""));
        // gh api --jq takes one jq program, not additional jq CLI flags such as --arg, so the query
        // must not rely on one.
        assertFalse(workflow.contains("--jq --arg"));
        // The artifact name alone is not proof of origin: any workflow in the repository could
        // upload an artifact under the same name. A recovered artifact is trusted only if the run
        // that produced it is this workflow file, triggered by a tag push for this exact tag.
        assertTrue(workflow.contains("run_is_trusted"));
        assertTrue(workflow.contains("\"$path\" == '.github/workflows/release.yml'"));
        assertTrue(workflow.contains("\"$event\" == 'push'"));
        assertTrue(workflow.contains("\"$head_branch\" == \"$GITHUB_REF_NAME\""));
        // Testing a command substitution directly with if/elif collapses every nonzero exit code to
        // plain "false", so a real API failure becomes indistinguishable from a clean "not found"
        // and
        // the release would proceed as though nothing had happened. The lookup's status is instead
        // captured explicitly and dispatched on the specific exit code.
        assertFalse(
                workflow.contains("if run_id=\"$(find_latest_trusted_run_with_artifact"),
                "the lookup's exit status must not be tested directly by if/elif");
        assertTrue(workflow.contains("set +e"));
        assertTrue(workflow.contains("status=$?"));
        assertTrue(workflow.contains("case \"$status\" in"));
        // A hard runner loss leaves no later step able to run, so the intent marker is durably
        // persisted, as its own artifact upload, strictly before the irreversible Central call is
        // even attempted — not only after the publish step finishes or fails.
        assertTrue(
                workflow.contains(
                        "Persist the pre-upload intent marker before any irreversible call"));
        assertTrue(workflow.contains("FOUNDRY_RELEASE_UPLOAD_ATTEMPT"));
        int recover = workflow.indexOf("- name: Recover any record of a completed upload");
        int attempt = workflow.indexOf("- name: Generate this attempt's identifier");
        int persistIntent =
                workflow.indexOf(
                        "- name: Persist the pre-upload intent marker before any irreversible call");
        int recordIntent =
                workflow.indexOf(
                        "- name: Record the pre-upload intent so a runner loss is still"
                                + " discoverable");
        int publish = workflow.indexOf("- name: Publish the verified staged release");
        int record = workflow.indexOf("- name: Record the completed upload so a re-run cannot");
        assertTrue(
                recover > 0
                        && attempt > recover
                        && persistIntent > attempt
                        && recordIntent > persistIntent
                        && publish > recordIntent
                        && record > publish);
    }

    @Test
    void releasingDocumentationDescribesTheAutomatedProcessAndRecovery() throws IOException {
        String documentation = read("docs/releasing.md");

        assertTrue(documentation.contains("./gradlew --write-locks resolveAndLockAll"));
        assertTrue(documentation.contains(WORKFLOW));
        for (String script : RELEASE_SCRIPTS) {
            assertTrue(documentation.contains(script), script + " must be documented");
        }
        assertTrue(documentation.contains("## What a human does"));
        assertTrue(documentation.contains("## Recovering a failed or partial release"));
        assertTrue(documentation.contains("release-signing"));
        assertTrue(documentation.contains("maven-central"));
        assertTrue(documentation.contains("games.cafecito.foundry"));
        assertTrue(documentation.contains("gradle.properties"));
        assertTrue(documentation.contains("release-provenance.json"));
        assertTrue(documentation.contains("gpg"));
        assertFalse(
                documentation.contains("Publish only from a clean, verified commit"),
                "the manual checklist is replaced by the automated process");
    }

    private static int occurrences(String value, String needle) {
        return value.split(Pattern.quote(needle), -1).length - 1;
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath));
    }
}
