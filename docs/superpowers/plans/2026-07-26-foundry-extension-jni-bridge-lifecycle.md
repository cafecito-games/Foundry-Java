# FoundryExtension/JNI Bridge Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and package a four-ABI `libfoundry_java.so` that exposes one stable
FoundryExtension entry point and a versioned, lifecycle-safe JNI bridge without linking or
packaging Foundry's Android host runtime.

**Architecture:** `FoundryJavaInitializer` loads the bridge and supplies the application class
loader, generated API identity, generator/runtime/bridge versions, and runtime callback object.
The C++ bridge owns process-global JNI references, creates opaque generation-bound contexts,
invokes Java through attach-aware callback leases, and drains those leases before releasing
references and FoundryExtension interface pointers. The bridge calls Foundry only through
`api/current/foundry_extension_interface.h`.

**Tech Stack:** Java 17, JNI, C++17, CMake/CTest, AddressSanitizer/UndefinedBehaviorSanitizer,
Android Gradle Plugin 8.10, Android NDK 29, API 36 instrumentation, Gradle/JUnit contract tests,
and `llvm-readelf` artifact inspection.

---

### Task 1: Lock the native and packaging contract with RED tests

**Files:**
- Create: `src/test/java/games/cafecito/foundry/build/NativeBridgeContractTest.java`
- Modify: `src/test/java/games/cafecito/foundry/build/RepositoryContractTest.java`

- [ ] **Step 1: Write the failing source and build contract**

Create tests that require the exact CMake/native/Java files, only
`#include "foundry_extension_interface.h"` as the engine header, all four Android ABI filters,
one fixed entry symbol, and these JNI exports:

```java
private static final Set<String> JNI_EXPORTS =
        Set.of(
                "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeBootstrapV1",
                "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeCreateContextV1",
                "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeInvokeCallbackV1",
                "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeInvokeCallbackOnThreadV1",
                "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeShutdownContextV1",
                "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeShutdownBridgeV1");
```

Require the AAR allowlist to contain only
`games/cafecito/foundry/java/FoundryJavaInitializer.class` and its nested callback adapter
classes in addition to the runtime dependency JAR, and reject every occurrence of
`libfoundry_android.so` in source, linker flags, and archive policy.

- [ ] **Step 2: Run the contract and verify RED**

Run:

```sh
./gradlew --no-daemon test --tests '*NativeBridgeContractTest'
```

Expected: FAIL because the native bridge files, ABI filters, JNI exports, and native archive
verification do not exist.

### Task 2: Implement opaque contexts and callback leases with native TDD

**Files:**
- Create: `foundry-java-android/src/main/cpp/foundry_java_runtime.h`
- Create: `foundry-java-android/src/main/cpp/foundry_java_handles.cpp`
- Create: `foundry-java-android/src/test/cpp/foundry_java_runtime_test.cpp`
- Create: `foundry-java-android/src/main/cpp/CMakeLists.txt`

- [ ] **Step 1: Write the failing native lifecycle tests**

Cover:

```cpp
CHECK(runtime.create_context() != 0);
CHECK(runtime.invoke(context, 7, {11, 13}) == 31);
CHECK(runtime.invoke(closed_context, 7, {}) == 0);
CHECK(runtime.invoke(other_generation_context, 7, {}) == 0);
CHECK(callbacks.reentrant_result == 42);
CHECK(callbacks.exceptions_contained == 1);
CHECK(callbacks.invalidations == 1);
```

Add a race test that blocks one callback, starts shutdown on another thread, proves shutdown has
not invalidated the context while the callback lease is active, then releases the callback and
proves shutdown finishes exactly once.

- [ ] **Step 2: Run the native tests and verify RED**

Run:

```sh
cmake -S foundry-java-android/src/main/cpp \
  -B foundry-java-android/build/native-host-red \
  -DFOUNDRY_JAVA_BUILD_TESTS=ON
cmake --build foundry-java-android/build/native-host-red
ctest --test-dir foundry-java-android/build/native-host-red --output-on-failure
```

Expected: configure/build FAIL because the runtime types are not implemented.

- [ ] **Step 3: Implement the minimal context runtime**

Define a `CallbackTarget` abstraction and `BridgeRuntime` with:

```cpp
using ContextHandle = std::uint64_t;

class CallbackTarget {
public:
    virtual ~CallbackTarget() = default;
    virtual bool initialize(ContextHandle context, int level) = 0;
    virtual void deinitialize(ContextHandle context, int level) = 0;
    virtual std::int64_t invoke(
            ContextHandle context,
            std::int64_t callback,
            const std::vector<std::int64_t> &arguments) = 0;
    virtual void invalidate(ContextHandle context) = 0;
};
```

Generate nonzero monotonic handles paired with a bridge generation. Remove a context from the
lookup map before shutdown, reject new leases, wait for active leases, call deinitialize and
invalidate, then release the callback owner. Catch all C++ exceptions at each callback boundary,
log one deterministic error, and return `false`/`0`.

- [ ] **Step 4: Run native tests and sanitizers GREEN**

Run:

```sh
cmake --build foundry-java-android/build/native-host-red
ctest --test-dir foundry-java-android/build/native-host-red --output-on-failure
cmake -S foundry-java-android/src/main/cpp \
  -B foundry-java-android/build/native-host-sanitized \
  -DFOUNDRY_JAVA_BUILD_TESTS=ON \
  -DFOUNDRY_JAVA_ENABLE_SANITIZERS=ON
cmake --build foundry-java-android/build/native-host-sanitized
ctest --test-dir foundry-java-android/build/native-host-sanitized --output-on-failure
```

Expected: both CTest runs PASS with no sanitizer report.

### Task 3: Implement JNI bootstrap, attachment, exceptions, and version validation

**Files:**
- Create: `foundry-java-android/src/main/java/games/cafecito/foundry/java/FoundryJavaInitializer.java`
- Create: `foundry-java-android/src/main/cpp/foundry_java_jni.cpp`
- Create: `foundry-java-android/src/main/cpp/foundry_java_exports.map`
- Modify: `foundry-java-android/src/main/cpp/CMakeLists.txt`

- [ ] **Step 1: Add Java bootstrap tests to the RED contract**

Require `FoundryJavaInitializer` to expose immutable constants:

```java
public static final String API_SHA256 =
        "85e91174c1a8a48629223d6459bb2ef595ad1da405b2ce88435c24fe221aec51";
public static final String GENERATOR_VERSION = "1";
public static final String RUNTIME_VERSION = "1";
public static final String BRIDGE_CONTRACT_VERSION = "1";
```

Require `initialize(FoundryBridgeCallbacks)` to pass the initializer class loader and those exact
values to `nativeBootstrapV1`.

- [ ] **Step 2: Implement `JNI_OnLoad` and bootstrap**

`JNI_OnLoad` records `JavaVM`, obtains the initializer's application class loader through
`Class.getClassLoader()`, and stores a global reference. `nativeBootstrapV1` verifies that loader
with `IsSameObject`, validates all four contract values, resolves callback method IDs, and commits
global references only after every check succeeds.

Use an attach guard:

```cpp
if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return nullptr;
    }
    attached_here = true;
}
```

Detach only when `attached_here` is true. After each Java call, detect, clear, and report a pending
exception through the installed Foundry logger before returning the documented default.

- [ ] **Step 3: Export only the stable native surface**

Use hidden default visibility and a linker version script whose global list is exactly:

```text
JNI_OnLoad;
JNI_OnUnload;
foundry_java_library_init;
Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeBootstrapV1;
Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeCreateContextV1;
Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeInvokeCallbackV1;
Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeInvokeCallbackOnThreadV1;
Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeShutdownContextV1;
Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeShutdownBridgeV1;
```

Wrap every JNI export in `try/catch (...)` so neither a C++ nor Java exception crosses JNI.

### Task 4: Implement and validate the FoundryExtension lifecycle

**Files:**
- Create: `foundry-java-android/src/main/cpp/foundry_java_entry.cpp`
- Modify: `foundry-java-android/src/main/cpp/foundry_java_runtime.h`
- Modify: `foundry-java-android/src/test/cpp/foundry_java_runtime_test.cpp`

- [ ] **Step 1: Add RED initialization-table tests**

Test null `get_proc_address`, library, and result pointers; a missing required function; JNI not
bootstrapped; and a complete fake interface table. The successful case must set:

```cpp
initialization.minimum_initialization_level = FOUNDRY_EXTENSION_INITIALIZATION_CORE;
initialization.userdata = state;
initialization.initialize = foundry_java_initialize;
initialization.deinitialize = foundry_java_deinitialize;
```

- [ ] **Step 2: Resolve and validate the interface table**

Resolve `print_error`, `classdb_register_extension_class5`,
`classdb_unregister_extension_class`, `string_name_new_with_utf8_chars`, and
`string_name_destroy`. Return false without mutating `r_initialization` when any function, JVM,
API hash, generator version, runtime version, or bridge version is unavailable or mismatched.

- [ ] **Step 3: Implement ordered initialize/deinitialize callbacks**

At core initialization, create one context and call Java initialize. On deinitialization:

1. remove the context from lookup and disable new callbacks;
2. wait for callback leases to drain;
3. call Java deinitialize;
4. call Java invalidate;
5. release Java global references;
6. clear Foundry interface pointers and the class-library pointer.

The callbacks for later Foundry initialization levels reuse the same live context and never
re-enable a draining context.

### Task 5: Build, instrument, and inspect all four Android ABIs

**Files:**
- Modify: `foundry-java-android/build.gradle.kts`
- Modify: `foundry-java-android/src/main/AndroidManifest.xml`
- Create:
  `foundry-java-android/src/androidTest/java/games/cafecito/foundry/java/FoundryJavaInstrumentation.java`
- Create: `gradle/verify-native-bridge.sh`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Configure the four-ABI native build**

Pin NDK `29.0.14206865`, use CMake, static libc++, and:

```kotlin
ndk {
    abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
}
externalNativeBuild {
    cmake {
        cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror")
        arguments += "-DANDROID_STL=c++_static"
    }
}
```

Register deterministic Gradle tasks for host CTest and sanitizer CTest, and make Android `check`
depend on them.

- [ ] **Step 2: Add instrumentation lifecycle coverage**

Use a platform `Instrumentation` subclass with no reflection discovery. It bootstraps the real
library, verifies reentrant callback return, invokes on a native thread to exercise attach/detach,
tests Java exception containment/default return, shuts the context down, and verifies callbacks
are rejected afterward.

- [ ] **Step 3: Inspect the AAR and every ELF**

`gradle/verify-native-bridge.sh` must require exactly one `libfoundry_java.so` under each of the
four ABI directories, reject `libfoundry_android.so`, compare the exact defined global dynamic
symbols with the stable export list, reject undefined host JNI symbols, and reject
`libfoundry_android.so`/`libjvm.so` from every `DT_NEEDED` entry.

- [ ] **Step 4: Run Android build and inspection**

Run:

```sh
./gradlew --no-daemon \
  :foundry-java-android:assembleDebug \
  :foundry-java-android:assembleDebugAndroidTest \
  :foundry-java-android:bundleReleaseAar \
  :foundry-java-android:nativeHostTest \
  :foundry-java-android:nativeSanitizerTest
bash gradle/verify-native-bridge.sh \
  foundry-java-android/build/outputs/aar/foundry-java-android-release.aar
```

Expected: Gradle succeeds; the verifier reports four ABIs, the exact symbol list, and no forbidden
payload/dependency.

### Task 6: Integrate CI, locks, documentation, and the complete gate

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `foundry-java-android/gradle.lockfile` only if resolution changes it
- Modify: `docs/architecture.md`
- Modify: `docs/android.md`
- Modify: `docs/memory.md`
- Modify: `src/test/java/games/cafecito/foundry/build/NativeBridgeContractTest.java`

- [ ] **Step 1: Pin CI native packages and gates**

Install `ndk;29.0.14206865` and `cmake;3.22.1`, run the native host/sanitizer tasks, assemble the
instrumentation APK, and run the AAR verifier. Preserve immutable action SHAs and the existing
configuration-cache and lock-drift gates.

- [ ] **Step 2: Document the authoritative lifecycle**

Document the fixed entry/JNI contracts, version validation, class-loader/global-reference
ownership, context invalidation, reentrancy, exception defaults, attachment rules, and the
shutdown ordering. State explicitly that the AAR neither packages nor links
`libfoundry_android.so`.

- [ ] **Step 3: Run the complete verification gate**

Run:

```sh
./gradlew --no-daemon clean check --configuration-cache \
  --configuration-cache-problems=fail
./gradlew --no-daemon \
  :foundry-java-android:assembleDebugAndroidTest \
  :foundry-java-android:bundleReleaseAar
bash gradle/verify-native-bridge.sh \
  foundry-java-android/build/outputs/aar/foundry-java-android-release.aar
./gradlew --write-locks resolveAndLockAll
git status --porcelain -- \
  gradle.lockfile ':(glob)**/gradle.lockfile' settings-gradle.lockfile
```

Expected: all builds and tests pass; the final lock status is empty.

- [ ] **Step 4: Run device instrumentation when an API 36 emulator is available**

Run:

```sh
./gradlew --no-daemon :foundry-java-android:connectedDebugAndroidTest
```

Expected: the lifecycle instrumentation finishes successfully. If no API 36 device is available,
record the exact environment gap and preserve the assembled test APK as evidence.

- [ ] **Step 5: Commit the clean pre-review checkpoint**

Run:

```sh
git add .github build.gradle.kts docs gradle foundry-java-android src/test
git commit -m "Implement FoundryExtension JNI bridge lifecycle"
git status --short --branch
```

Expected: one focused commit and a clean `issue-4` worktree. Do not push or open a PR before the
root spec/quality and Cursor review sequence.
