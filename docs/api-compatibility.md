# API Compatibility Inputs

Foundry-Java pins the public FoundryExtension inputs from the published
[`v0.1.0-alpha.14`](https://github.com/cafecito-games/Foundry/releases/tag/v0.1.0-alpha.14)
API archive. The accepted producer commit is
`b9a5e66c21f8f7b707a9e526ca20557485c53227`, the API version is
`0.1.0-alpha.14`, and the compatibility minimum is `0.1.0`.

The immutable source record is [`api/current/provenance.json`](../api/current/provenance.json).
It records the release and archive URLs, the Foundry source repository and MIT license, producer
commit and version, API/ABI minimum, generator version `1`, bridge-contract version `1`, and every
accepted SHA-256:

| Input | SHA-256 |
| --- | --- |
| Release API archive | `b6f44138e71e2b7c0a863457a26734fb2af812f080845fbc1d8a2fca3d2c1c44` |
| `extension_api.json` | `48af7d0e8fbbbc615d985db39c135402e5120649865cc21e43676da5ee65332b` |
| `foundry_extension_interface.h` | `ecf9a1f1e6b2642385a521725313efb2baea8b81fcac9dc837f55a4b90498991` |
| Compatibility manifest | `78fe316fd3c02b5b4c452b1cf966040b37f857d18312bcca19d6b2b8b89b021d` |

`ApiInputs.load` checks all required identities and hashes before returning input text. The API
parser then rejects duplicate JSON keys, unknown top-level or nested constructs, unknown schema
enumerations, missing or malformed stable identities, and duplicate source identities. A schema
error includes both the JSON path and the nearest validated stable entity identity; diagnostic
values escape controls and backslashes so failures remain single-line. Models and their
collections are immutable, object keys are canonicalized, named entity collections are ordered by
source identity, and argument order remains semantic. Every entity records its parent edge and
normalized ordinal so semantic order is explicit and reconstructable. Integer-valued schema fields
use field-specific ranges. Unsigned hashes, indexes, sizes, and offsets require canonical unsigned
integer lexemes (so `-0` is rejected), while hashes retain the full unsigned 64-bit range without
narrowing.

## Exhaustive classification

The compatibility manifest accounts for every parsed source entity exactly once:

| Source category | Entities |
| --- | ---: |
| Built-in member offsets | 252 |
| Built-in sizes | 164 |
| Built-in classes and members | 3,333 |
| Engine classes and members | 53,235 |
| Global constants | 11 |
| Global enums and values | 542 |
| Native structures | 14 |
| Singletons | 39 |
| Utility functions and arguments | 309 |
| **Total** | **57,899** |

The approved statuses are `supported`, `excluded-language`, `excluded-platform`, and
`excluded-upstream`. Every entry requires a stable reason code and source identity; missing,
duplicate, extra, blank-reason, or unknown-status entries fail generation.
The production generator consumes this verified manifest and requires its API hash, generator
version, bridge-contract version, identity set, and classifications to match the accepted inputs;
it never synthesizes replacement classifications.

All 57,899 entries are `supported` with the stable compatibility reason
`WS5_MODEL_AND_GENERATOR_REPRESENTABLE`. The later runtime-generation pass consumes that exhaustive
model to emit the public Java bindings described below. Native address translation and Android host
integration remain bridge responsibilities.

## Deterministic generation

Generation groups every parsed identity under exactly one public API root. It emits 1,279 Java
sources representing 1,297 accepted roots, plus provenance, public-root inventory, object
registration, and strongly typed ABI pointer helpers. Coverage is accepted only when the parsed
identity set, generated identity set, and manifest identity set are exactly equal. Generated files
include the same standard producer commit/version and input/manifest hash header; dynamic comment
values, including entity identities and provenance, use RFC 4648 base64 so Java Unicode
preprocessing cannot turn input text into declarations. They contain no timestamp or absolute path.
No hash-suffixed descriptor classes are published in the production JAR or Javadoc.

The generated surface uses canonical engine-class wrappers for singleton results, derives object
ownership and construction from `is_refcounted` and `is_instantiable`, exposes typed signals, uses
the runtime value types as the single built-in representation, provides query APIs for size/member
layout tables, and provides usable context-bound wrappers for all 14 native structures. Opaque
pointer families use distinct generated marker types instead of one interchangeable raw handle.
The binary API gate inventories runtime/types in detail and hashes the complete sorted public
`javap` output for generated bindings, so generated public API drift cannot bypass compatibility
review.

Tests generate into two independent clean directories and byte-compare every relative path and
file hash. They repeat from canonical reordered input, regenerate the complete accepted API, verify
the checked-in manifest byte-for-byte, and compile all 1,279 generated Java sources with the
supported JDK 17 compiler.

`foundry-java-generator` publishes `foundry-java-api-model` as an API dependency. The repository
compiles a consumer that depends only on the generator to prove its public model types remain
available transitively in both Gradle and Maven publication metadata.

This pipeline consumes only the public `FoundryExtension` API description and C interface header.
It neither reads private Android host JNI nor packages or links `libfoundry_android.so`.
