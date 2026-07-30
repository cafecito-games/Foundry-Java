# Releasing

Releasing Foundry-Java is automated and tag-driven. All artifacts use Maven group
`games.cafecito.foundry`. Pushing an annotated tag `vX.Y.Z` that names the version declared in
[`gradle.properties`](../gradle.properties) runs the complete gate set, stages a signed release,
proves that release reproducible and completely verified, and only then uploads it to Maven Central.

## What the pipeline does

[`.github/workflows/release.yml`](../.github/workflows/release.yml) calls the shared
[`.github/workflows/gates.yml`](../.github/workflows/gates.yml) workflow before its stage and
publish jobs. Nothing is signed until both called gate jobs pass, and nothing is uploaded until the
staged release is verified.

1. **`gates / Host gate`** — configuration-cache reuse from clean outputs: it executes the full
   Gradle `clean check` once, then dry-runs the identical graph in a fresh process to prove reuse.
   The gate also runs `:foundry-java-runtime:verifyRuntimeApi`, the parity oracle
   `:foundry-java-runtime:verifyGeneratedRealization`, the native host and sanitizer tests, the
   native ABI layout test, the AAR native-bridge inspection, regenerated dependency locks, and
   [`gradle/verify-release-preconditions.sh`](../gradle/verify-release-preconditions.sh).
2. **`gates / Device gate on API 36`** — production startup twice in fresh processes on an API 36
   emulator, the Java and Kotlin conformance matrix as consumer samples, and the engine-loaded API
   36 conformance gate. It carries a 150-minute budget because the engine gate downloads a roughly
   1.1 GB export template and builds five exports.
3. **`stage`** — [`gradle/verify-release-reproducibility.sh`](../gradle/verify-release-reproducibility.sh)
   stages the tag twice through [`gradle/stage-release.sh`](../gradle/stage-release.sh) and compares
   the results byte for byte, then
   [`gradle/verify-staged-release.sh`](../gradle/verify-staged-release.sh) verifies every signature,
   every checksum, every POM, and every Gradle module metadata document in the staged repository.
4. **`publish`** — [`gradle/upload-staged-release.sh`](../gradle/upload-staged-release.sh) checks
   every coordinate against Maven Central and uploads the staged release to the Central Portal.

### Refusals

A release refuses to proceed, before any signing happens, when:

- the tag does not match the `foundryVersion` declared in `gradle.properties`;
- that declared version is not a release version, including any `SNAPSHOT`;
- the tag does not exist or does not point at `HEAD`;
- the tagged commit is not already contained in `origin/main`;
- a dependency lock is dirty after `./gradlew --write-locks resolveAndLockAll`;
- the working tree is not clean, including untracked files.

It refuses to upload when the staged release carries no successful verification summary, and it
refuses the whole release — rather than republishing part of it — when any coordinate is already
published at the target.

### Reproducibility

Every archive task uses normalized timestamps and entry order, and Javadoc is generated with
`-notimestamp`, so staging the same tag twice produces byte-identical artifacts. Detached OpenPGP
signatures are the one exception, because a signature packet records its own creation time; the
reproducibility summary states that exclusion and counts the signatures it covers.

### Provenance

Each staged release carries `release-provenance.json`: the release tag, the binding version, the
source commit, the engine API version and hashes, the accepted producer commit, the generator and
bridge-contract versions, the SHA-256 of the binding-neutral surface manifest, and a SHA-256 for
every staged file. The surface manifest itself is a build output, so it is copied into the staged
release next to the record that names it, together with `signing-public-key.asc`, the public half of
the key that signed the release.

### Topology

[`gradle/release-topology.txt`](../gradle/release-topology.txt) declares the exact published set: ten
coordinates, nine modules plus the Gradle plugin marker. Every module publishes a main archive,
sources, and Javadoc. Nothing outside that set may appear in a staged release.

## What a human does

1. Decide the version and update `foundryVersion` in `gradle.properties` on `main`. Review dependency
   locks deliberately, regenerating with `./gradlew --write-locks resolveAndLockAll` when
   dependencies changed, and commit the result.
2. Confirm the merge commit is green on `main`.
3. Create and push the annotated tag:
   `git tag -a vX.Y.Z -m 'Foundry-Java vX.Y.Z' && git push origin vX.Y.Z`.
4. Approve the `release-signing` and `maven-central` deployment environments when GitHub asks.
5. Confirm the signing key fingerprint in `verification-summary.json` is the published Foundry-Java
   release key.
6. Release the Central Portal deployment from `USER_MANAGED` to published, and confirm the
   coordinates resolve.

Nothing else is manual. Do not publish from a workstation.

### Credentials

Signing and publishing credentials exist only as encrypted GitHub secrets scoped to the deployment
environment that needs them, and this workflow has no pull-request trigger, so no pull-request
workflow can request either environment.

| Secret | Environment | What it is |
| --- | --- | --- |
| `FOUNDRY_SIGNING_KEY` | `release-signing` | the ASCII-armored OpenPGP signing key |
| `FOUNDRY_SIGNING_PASSWORD` | `release-signing` | that key's password, which may be empty |
| `FOUNDRY_CENTRAL_PORTAL_TOKEN` | `maven-central` | the Central Portal upload token |

The expected primary key fingerprint is public data, so it is the repository variable
`FOUNDRY_SIGNING_KEY_FINGERPRINT` rather than a secret. Verification requires it and rejects any
signature made by another key, so signature checking is an independent check rather than a
consistency check against the key the signer supplied.

The signing key reaches Gradle as an `ORG_GRADLE_PROJECT_` environment value and the Portal token
reaches `curl` on standard input, so neither ever appears on a command line, in a process listing, or
in a log. Nothing in the pipeline echoes a secret.

## Proving the pipeline without publishing

[`gradle/run-release-staging-dry-run.sh`](../gradle/run-release-staging-dry-run.sh) exercises the
whole pipeline against a local staging target. It generates an ephemeral `gpg` signing key, stages the
declared version twice and compares them, verifies every signature and checksum against that key,
uploads to a staging repository, and requires a second upload of the same release to fail. It accepts
no target other than `staging` and never contacts Maven Central. It exercises the real preconditions,
so the declared version has to be tagged locally first:

```sh
bash gradle/run-release-staging-dry-run.sh
```

It exercises the real preconditions, so it creates the release tag at `HEAD` itself and deletes it
again; it never pushes one. A tag that already exists somewhere else is reported rather than moved.

A release requires its commit to be contained in `origin/main`. To dry-run from a branch that is not
merged yet, point the containment check at that branch with
`FOUNDRY_RELEASE_CONTAINING_REF=refs/heads/<branch>`. A real release never sets it, so the default
`refs/remotes/origin/main` is what a tag push is checked against.

The same script runs from the workflow's manual `dry_run` dispatch. It needs `gpg`, `jq`, `shasum`,
the Android SDK, and the pinned NDK and CMake, exactly like the gates.

## Recovering a failed or partial release

The pipeline is built so that a failure leaves nothing half-published, and every recovery is a re-run
rather than a repair.

- **A gate job failed.** Nothing was signed or uploaded. Fix the cause on `main`, delete and re-create
  the tag on the new commit, and push it again.
- **Staging failed, including a reproducibility difference.** Nothing was uploaded. The staging
  evidence artifact carries the reproducibility summary naming every differing path. Fix the source of
  the difference and re-run.
- **Verification failed.** Nothing was uploaded, by construction: the uploader refuses a staged
  release without a successful verification summary. Read the rejection lines, which name the exact
  file and the exact problem, and re-run.
- **The upload refused to start because a coordinate is already published.** The release is already
  out, wholly or in part. Never overwrite a published coordinate. Choose the next patch version,
  update `gradle.properties`, and release that instead.
- **The upload failed during transfer.** The Central Portal deployment is one bundle and stays
  `USER_MANAGED` until a human releases it. Drop the incomplete deployment in the Portal, then re-run
  the `publish` job for the same tag; the staged release is attached to the `stage` job as an artifact
  and is byte-identical to what was verified. If the upload had already been accepted, the
  `foundry-java-release-upload-record-<tag>` artifact carries its deployment identifier and the re-run
  refuses rather than submitting a second bundle. That artifact is version-scoped and searched for
  across the whole repository, not just the current run, so a fresh tag-push run also refuses instead
  of re-uploading; delete that artifact only after dropping the deployment. An artifact name alone is
  not trusted: only one produced by this workflow file, triggered by a tag push for the exact tag being
  released, is ever recovered, so an unrelated workflow run cannot forge a record or a marker.
- **The runner died between the upload and the record being written.** The upload record is written
  before the irreversible Central call as a pre-upload intent marker (`upload-intent.json`), and the
  completed record (`upload-summary.json`) is written once the upload finishes. The `publish` job
  persists the intent marker as its own `foundry-java-release-upload-intent-<tag>` artifact, tagged with
  a per-attempt token, in a dedicated step before the publish step even starts, so it survives even a
  hard loss of the runner, not only a failure inside the publish step itself. That artifact is never
  overwritten, so it is never at risk of the delete-then-upload gap that an overwrite would open; the
  completed record is a second, separate `foundry-java-release-upload-record-<tag>` artifact. If a
  re-run recovers an intent marker with no completed record, it refuses: this is ambiguous, not "safe
  to retry" — the bundle may already have been accepted. Check the Central Portal directly for a
  deployment matching this version. Once you are certain nothing was accepted, delete the
  `foundry-java-release-upload-intent-<tag>` artifact itself (`gh api -X DELETE
  repos/<owner>/<repo>/actions/artifacts/<id>`, found by listing artifacts with that name), not just
  the local `upload-intent.json` file: every re-run downloads that artifact back into the staging
  directory, so leaving it in place makes any re-run refuse again regardless of the local file. Only
  after the artifact itself is gone can a re-run generate a fresh attempt and proceed. Never resubmit
  while this is unresolved.
- **A published release turns out to be wrong.** Maven Central coordinates are immutable. Publish a
  new patch version; do not attempt to replace one.
