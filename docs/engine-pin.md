# Engine Pin and the Engine-Loaded Conformance Gate

Foundry-Java does not build the engine. The engine-loaded API 36 conformance gate downloads one
pinned Foundry release, exports an acceptance project against the Foundry-Java artifacts built from
the commit under test, installs the result on an API 36 emulator, and requires the running game to
reach a Java-defined class through the engine's class database.

## The pin

[`gradle/engine-pin.json`](../gradle/engine-pin.json) is the single record of the pinned engine. It
pins release [`v0.1.0-alpha.14`](https://github.com/cafecito-games/Foundry/releases/tag/v0.1.0-alpha.14),
whose tag commit `b9a5e66c21f8f7b707a9e526ca20557485c53227` is
[Foundry #1338](https://github.com/cafecito-games/Foundry/pull/1338), the engine-side binding
loader. This is the earliest release whose export templates can load the binding at all; `alpha.13`
was cut before #1338 merged.

| Asset | Size | SHA-256 |
| --- | ---: | --- |
| `Foundry_v0.1.0-alpha.14_linux.x86_64.zip` | 75,400,574 | `af8dd877477189214505fd2b2640456dc361310e62aab79b297537c08e3ef91d` |
| `Foundry_v0.1.0-alpha.14_export_templates.tpz` | 1,147,349,658 | `6a5cc2bb5b8b4cc7f48bcdf51575645fca408ac62e25dad0691d71f3a117a03f` |
| `Foundry_v0.1.0-alpha.14_api.zip` | 1,216,523 | `b6f44138e71e2b7c0a863457a26734fb2af812f080845fbc1d8a2fca3d2c1c44` |

The pin also records the pinned path, size, and digest of the upstream device acceptance tool,
`platform/android/android_device_acceptance.py`. Foundry-Java owns a thin harness only: the deep
device assertions are upstream property, never forked or vendored. That tool is not one file — its
`verify-apks` path loads its sibling host-contract module and derives the Java members the native
host resolves through JNI from the engine's own native sources — so the harness materializes the
pinned `platform/android` directory at the pinned commit through a blobless sparse checkout, which
keeps the tool at its real repository path with every companion file it reads. The commit is the
integrity anchor, because git object names are content addresses; the checked-out revision is still
asserted to be the pinned commit, and the tool's own size and digest are still verified.

[`gradle/fetch-pinned-engine.sh`](../gradle/fetch-pinned-engine.sh) resolves the pin. It first
requires the pinned release, producer commit, and API archive digest to equal the vendored API
identity in [`api/current/provenance.json`](../api/current/provenance.json), so a pin bump that
forgets to re-vendor the API cannot reach an export. It then verifies every asset against its pinned
size and SHA-256 before opening it. The export template archive is roughly 1.1 GB, so it is cached
by release tag and asset digest rather than downloaded per job; the digest is recomputed on a cache
hit too, and a mismatch deletes the cached file and fails before any export runs. Only
`android_source.zip` is extracted from the archive: the Gradle-built export compiles the application
from that source template and takes the engine host libraries from the AARs it carries.

## What the gate proves

[`gradle/run-engine-loaded-conformance-gate.sh`](../gradle/run-engine-loaded-conformance-gate.sh)
publishes Foundry-Java from the commit under test into the local bootstrap repository, builds the
annotated acceptance module in the standalone [`acceptance/`](../acceptance) consumer build, and
exports four applications: debug and minified release, each with the default application ID
`games.cafecito.foundry.game` and the custom application ID `dev.example.foundryjava`. It never
resolves a published or cached release of this repository.

The only accepted evidence that the engine loaded the binding is the runtime marker
`FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_READY`. [`acceptance/project/main.fs`](../acceptance/project/main.fs)
prints it only after `ClassDB` resolved `FoundryJavaEngineProbe`, instantiated it, and returned the
value the Java method computed. Absence of a crash is not evidence. The gate fails fast on
`FOUNDRY_JAVA_PLATFORM_EXTENSION_LOAD_FAILED` and on the established runtime failure signatures
instead of waiting out the marker timeout.

Before any of that, the gate runs its own negative proof. It builds the same acceptance module from
the fixture copy whose engine class registration is disabled — identical descriptor, registry index,
keep rules, and bridge ABIs, differing only in the registered engine class name — exports it, and
requires the gate to fail. A run that passes there means the gate is asserting packaging instead of
behaviour, which is exactly the failure Foundry #1338 documented, and it is reported as a gate
failure.

Statically, [`gradle/verify-exported-abi-payloads.sh`](../gradle/verify-exported-abi-payloads.sh)
inspects all four exported ABIs of the debug default-ID application for the expected
`libfoundry_java.so`, the exact exported symbol surface shared with
[`gradle/verify-native-bridge.sh`](../gradle/verify-native-bridge.sh), forbidden host JNI imports,
forbidden dynamic dependencies, exactly one `assets/FoundryJava.foundryextension` and one
`assets/foundry_java/registry-index-v2.txt` with no duplicate payloads, and narrow minification keep
rules. Foundry-Java never packages, links, loads, or redistributes `libfoundry_android.so`: the
exported application legitimately contains the engine's own copy, so absence is asserted on the
artifacts this repository produces and on the bridge's dynamic dependencies rather than inferred
from the final application. The device leg executes on the emulator's `x86_64` ABI.

Every manifest, diff, symbol dump, report, application-ID and manifest print, bootstrap log, and
logcat is written under `${RUNNER_TEMP}/foundry-java-engine-gate` and uploaded on success and on
failure alike.

## Bumping the pin

1. Re-vendor the engine API for the new release first, so `api/current/provenance.json` records the
   new release tag, producer commit, and API archive digest. The gate refuses to run while the pin
   and the vendored identity disagree.
2. Read the new release's published asset digests with
   `gh release view <tag> --repo cafecito-games/Foundry --json assets`, and update `release_tag`,
   `release_url`, `download_url_prefix`, `producer_commit`, `api_version`, and every asset `name`,
   `size`, and `sha256` in `gradle/engine-pin.json`.
3. Update `device_acceptance.size` and `device_acceptance.sha256` to the size and SHA-256 of
   `platform/android/android_device_acceptance.py` at the new producer commit.
4. Update the cache key in `.github/workflows/gates.yml`, this page, and the constants in
   `src/test/java/games/cafecito/foundry/build/EngineLoadedConformanceGateContractTest.java`. The
   cache key contains the release tag and the template digest, so it can never survive a bump.
5. Run `./gradlew clean check` and let continuous integration run the gate.

## Reproducing the gate locally

The gate needs Linux, an API 36 emulator with `x86_64` system images, the Android SDK with NDK
`29.0.14206865` and `cmdline-tools`, `jq`, `python3`, and roughly 3 GB of free disk for the pinned
release.

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17
export RUNNER_TEMP="$(mktemp -d)"
export FOUNDRY_ENGINE_CACHE="${HOME}/.cache/foundry-engine"
bash gradle/run-engine-loaded-conformance-gate.sh emulator-5554
```

The first run downloads the pinned editor and the 1.1 GB export template archive into
`FOUNDRY_ENGINE_CACHE`; later runs reuse them and still verify their digests. To materialize the
pinned engine without running the gate, use
`bash gradle/fetch-pinned-engine.sh "$(mktemp -d)"` and read the `engine-manifest.json` it writes.
