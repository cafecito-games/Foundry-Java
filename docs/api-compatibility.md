# API Compatibility Inputs

Foundry-Java pins the public FoundryExtension inputs from the published
[`v0.1.0-alpha.8`](https://github.com/cafecito-games/Foundry/releases/tag/v0.1.0-alpha.8)
API archive. The accepted producer commit is
`3923e920b2fb6db68f82dfdab2bf7b1df125492d`, the API version is
`0.1.0-alpha.8`, and the compatibility minimum is `0.1.0`.

The immutable source record is [`api/current/provenance.json`](../api/current/provenance.json).
It records the release and archive URLs, the Foundry source repository and MIT license, producer
commit and version, API/ABI minimum, generator version `1`, bridge-contract version `1`, and every
accepted SHA-256:

| Input | SHA-256 |
| --- | --- |
| Release API archive | `5e8dd7cea34051297c2ca89ec05fc2d50ee921b156d220b6007cc274d769beec` |
| `extension_api.json` | `85e91174c1a8a48629223d6459bb2ef595ad1da405b2ce88435c24fe221aec51` |
| `foundry_extension_interface.h` | `ecf9a1f1e6b2642385a521725313efb2baea8b81fcac9dc837f55a4b90498991` |
| Compatibility manifest | `1bd2d0bed9e1d7a7bb6fc4dcb6fd0fcb91202e7f468162d8979552c7028fd7e1` |

`ApiInputs.load` checks all required identities and hashes before returning input text. The API
parser then rejects duplicate JSON keys, unknown top-level or nested constructs, unknown schema
enumerations, missing or malformed stable identities, and duplicate source identities. A schema
error includes both the JSON path and the nearest stable entity identity. Models and their
collections are immutable, object keys are canonicalized, named entity collections are ordered by
source identity, and argument order remains semantic.

## Exhaustive classification

The compatibility manifest accounts for every parsed source entity exactly once:

| Source category | Entities |
| --- | ---: |
| Built-in member offsets | 252 |
| Built-in sizes | 164 |
| Built-in classes and members | 3,333 |
| Engine classes and members | 53,240 |
| Global constants | 11 |
| Global enums and values | 542 |
| Native structures | 14 |
| Singletons | 39 |
| Utility functions and arguments | 309 |
| **Total** | **57,904** |

The approved statuses are `supported`, `excluded-language`, `excluded-platform`, and
`excluded-upstream`. Every entry requires a stable reason code and source identity; missing,
duplicate, extra, blank-reason, or unknown-status entries fail generation.

For this workstream, all 57,904 entries are `supported` with reason
`WS5_MODEL_AND_GENERATOR_REPRESENTABLE`. Here, `supported` means the WS5 model can represent the
entity without loss and the WS5 generator emits it deterministically. It does not claim that the
runtime-callable wrappers or JNI bridge scheduled for later workstreams are already implemented.

## Deterministic generation

Generation groups every parsed identity under exactly one per-root Java descriptor and emits
1,298 descriptors plus provenance and registration catalogs. Coverage is accepted only when the
parsed identity set, generated identity set, and manifest identity set are exactly equal. Generated
files include the producer commit/version and input/manifest hashes; they contain no timestamp or
absolute path.

Tests generate into two independent clean directories and byte-compare every relative path and
file hash. They repeat from canonical reordered input, regenerate the complete accepted API, verify
the checked-in manifest byte-for-byte, and compile all 1,300 generated Java sources with the
supported JDK 17 compiler.

This pipeline consumes only the public `FoundryExtension` API description and C interface header.
It neither reads private Android host JNI nor packages or links `libfoundry_android.so`.
