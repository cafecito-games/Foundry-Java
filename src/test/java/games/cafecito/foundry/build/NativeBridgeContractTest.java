package games.cafecito.foundry.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class NativeBridgeContractTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final String ENTRY_SYMBOL = "foundry_java_library_init";
    private static final List<String> JNI_SYMBOLS =
            List.of(
                    "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeBootstrapV1",
                    "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeCreateContextV1",
                    "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeInvokeCallbackV1",
                    "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeInvokeCallbackOnThreadV1",
                    "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeShutdownContextV1",
                    "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeShutdownBridgeV1",
                    "Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeCallV1",
                    "Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeDecodeVariantV1",
                    "Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeEncodeVariantV1",
                    "Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeIsObjectValidV1",
                    "Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeObjectTypeV1",
                    "Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeInstantiateV1",
                    "Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeRetainV1",
                    "Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeReleaseV1",
                    "Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeSingletonV1",
                    "Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeReportCallbackExceptionV1",
                    "Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeRegisterExtensionClassV1",
                    "Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeUnregisterExtensionClassV1");
    private static final Set<String> INITIALIZER_NATIVE_METHODS =
            Set.of(
                    "nativeBootstrapV1",
                    "nativeCreateContextV1",
                    "nativeInvokeCallbackV1",
                    "nativeInvokeCallbackOnThreadV1",
                    "nativeShutdownContextV1",
                    "nativeShutdownBridgeV1");
    private static final Map<String, String> NATIVE_ENGINE_DESCRIPTORS =
            Map.ofEntries(
                    Map.entry(
                            "nativeCallV1",
                            "(JJLgames/cafecito/foundry/runtime/FoundryNativeDispatch;"
                                    + "[Lgames/cafecito/foundry/types/Variant;)"
                                    + "Lgames/cafecito/foundry/runtime/FoundryEngine$CallResult;"),
                    Map.entry(
                            "nativeDecodeVariantV1", "(JJ)Lgames/cafecito/foundry/types/Variant;"),
                    Map.entry(
                            "nativeEncodeVariantV1", "(JLgames/cafecito/foundry/types/Variant;)J"),
                    Map.entry("nativeIsObjectValidV1", "(JJ)Z"),
                    Map.entry("nativeObjectTypeV1", "(JJ)Ljava/lang/String;"),
                    Map.entry("nativeInstantiateV1", "(JLjava/lang/String;)J"),
                    Map.entry("nativeRetainV1", "(JJ)V"),
                    Map.entry("nativeReleaseV1", "(JJ)V"),
                    Map.entry("nativeSingletonV1", "(JLjava/lang/String;)J"),
                    Map.entry("nativeReportCallbackExceptionV1", "(JJLjava/lang/Throwable;)V"),
                    Map.entry(
                            "nativeRegisterExtensionClassV1",
                            "(JLgames/cafecito/foundry/runtime/FoundryClassDescriptor;)V"),
                    Map.entry("nativeUnregisterExtensionClassV1", "(JLjava/lang/String;)V"));
    private static final Map<String, String> INITIALIZER_KEEP_SIGNATURES =
            Map.of(
                    "nativeBootstrapV1",
                    "private static native boolean nativeBootstrapV1(java.lang.ClassLoader, "
                            + "games.cafecito.foundry.runtime.FoundryBridgeCallbacks, "
                            + "java.lang.String, java.lang.String, java.lang.String, "
                            + "java.lang.String);",
                    "nativeCreateContextV1",
                    "private static native long nativeCreateContextV1();",
                    "nativeInvokeCallbackV1",
                    "private static native long nativeInvokeCallbackV1(long, long, long[]);",
                    "nativeInvokeCallbackOnThreadV1",
                    "private static native long nativeInvokeCallbackOnThreadV1(long, long, long[]);",
                    "nativeShutdownContextV1",
                    "private static native boolean nativeShutdownContextV1(long);",
                    "nativeShutdownBridgeV1",
                    "private static native void nativeShutdownBridgeV1();");
    private static final Map<String, String> NATIVE_ENGINE_KEEP_SIGNATURES =
            Map.ofEntries(
                    Map.entry(
                            "nativeCallV1",
                            "private static native "
                                    + "games.cafecito.foundry.runtime.FoundryEngine$CallResult "
                                    + "nativeCallV1(long, long, "
                                    + "games.cafecito.foundry.runtime.FoundryNativeDispatch, "
                                    + "games.cafecito.foundry.types.Variant[]);"),
                    Map.entry(
                            "nativeDecodeVariantV1",
                            "private static native games.cafecito.foundry.types.Variant "
                                    + "nativeDecodeVariantV1(long, long);"),
                    Map.entry(
                            "nativeEncodeVariantV1",
                            "private static native long nativeEncodeVariantV1(long, "
                                    + "games.cafecito.foundry.types.Variant);"),
                    Map.entry(
                            "nativeIsObjectValidV1",
                            "private static native boolean nativeIsObjectValidV1(long, long);"),
                    Map.entry(
                            "nativeObjectTypeV1",
                            "private static native java.lang.String "
                                    + "nativeObjectTypeV1(long, long);"),
                    Map.entry(
                            "nativeInstantiateV1",
                            "private static native long nativeInstantiateV1(long, "
                                    + "java.lang.String);"),
                    Map.entry(
                            "nativeRetainV1",
                            "private static native void nativeRetainV1(long, long);"),
                    Map.entry(
                            "nativeReleaseV1",
                            "private static native void nativeReleaseV1(long, long);"),
                    Map.entry(
                            "nativeSingletonV1",
                            "private static native long nativeSingletonV1(long, java.lang.String);"),
                    Map.entry(
                            "nativeReportCallbackExceptionV1",
                            "private static native void nativeReportCallbackExceptionV1("
                                    + "long, long, java.lang.Throwable);"),
                    Map.entry(
                            "nativeRegisterExtensionClassV1",
                            "private static native void nativeRegisterExtensionClassV1(long, "
                                    + "games.cafecito.foundry.runtime.FoundryClassDescriptor);"),
                    Map.entry(
                            "nativeUnregisterExtensionClassV1",
                            "private static native void nativeUnregisterExtensionClassV1(long, "
                                    + "java.lang.String);"));
    private static final Set<String> NATIVE_BRIDGE_FILES =
            Set.of(
                    "CMakeLists.txt",
                    "cmake/GenerateFoundryJavaAbiLayout.cmake",
                    "foundry_java_abi_layout.h.in",
                    "foundry_java_contract.h.in",
                    "foundry_java_entry.cpp",
                    "foundry_java_exports.map",
                    "foundry_java_handles.cpp",
                    "foundry_java_interface.cpp",
                    "foundry_java_interface.h",
                    "foundry_java_jni.cpp",
                    "foundry_java_runtime.h",
                    "foundry_java_transport.cpp",
                    "foundry_java_transport.h");
    private static final List<String> BRIDGE_FILES =
            List.of(
                    "foundry-java-android/src/main/consumer-rules.pro",
                    "foundry-java-android/src/main/java/games/cafecito/foundry/java/FoundryJavaInitializer.java",
                    "foundry-java-android/src/main/java/games/cafecito/foundry/java/FoundryNativeEngine.java",
                    "foundry-java-android/src/test/cpp/foundry_java_runtime_test.cpp",
                    "foundry-java-android/src/androidTest/java/games/cafecito/foundry/java/FoundryJavaInstrumentation.java",
                    "gradle/verify-native-bridge.sh");

    @Test
    void nativeBridgeFilesExist() {
        for (String path : BRIDGE_FILES) {
            assertTrue(Files.isRegularFile(ROOT.resolve(path)), path + " must exist");
        }
    }

    @Test
    void nativeBridgeSourceInventoryIsExact() throws IOException {
        Path nativeRoot = ROOT.resolve("foundry-java-android/src/main/cpp");
        Set<String> actual;
        try (var paths = Files.walk(nativeRoot)) {
            actual =
                    paths.filter(Files::isRegularFile)
                            .map(path -> nativeRoot.relativize(path).toString().replace('\\', '/'))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        assertEquals(NATIVE_BRIDGE_FILES, actual);
    }

    @Test
    void bridgeUsesOnePublicFoundryExtensionEntryAndVersionedJni() throws IOException {
        String cmake = read("foundry-java-android/src/main/cpp/CMakeLists.txt");
        String entry = read("foundry-java-android/src/main/cpp/foundry_java_entry.cpp");
        String jni = read("foundry-java-android/src/main/cpp/foundry_java_jni.cpp");
        String exports = read("foundry-java-android/src/main/cpp/foundry_java_exports.map");
        String nativeSources =
                entry
                        + jni
                        + read("foundry-java-android/src/main/cpp/foundry_java_runtime.h")
                        + read("foundry-java-android/src/main/cpp/foundry_java_handles.cpp");

        assertTrue(cmake.contains("add_library(foundry_java SHARED"));
        assertTrue(nativeSources.contains("#include \"foundry_extension_interface.h\""));
        assertFalse(nativeSources.contains("platform/android"));
        assertFalse(nativeSources.contains("Foundry-Android"));
        assertFalse(nativeSources.contains("libfoundry_android"));
        assertEquals(1, occurrences(exports, ENTRY_SYMBOL + ";"));
        assertEquals(Set.copyOf(JNI_SYMBOLS), exportedJniSymbols(exports));
        for (String symbol : JNI_SYMBOLS) {
            assertEquals(1, occurrences(exports, symbol + ";"), symbol);
            assertTrue(jni.contains(symbol), symbol);
        }
    }

    @Test
    void nativeEnginePinsExactVersionedJniDescriptorsAndNarrowConsumerRules() throws IOException {
        String initializer =
                read(
                        "foundry-java-android/src/main/java/games/cafecito/foundry/java/"
                                + "FoundryJavaInitializer.java");
        String nativeEngine =
                read(
                        "foundry-java-android/src/main/java/games/cafecito/foundry/java/"
                                + "FoundryNativeEngine.java");
        String rules =
                read("foundry-java-android/src/main/consumer-rules.pro").replaceAll("\\s+", " ");
        var nativeMethods = new java.util.HashSet<>(INITIALIZER_NATIVE_METHODS);
        nativeMethods.addAll(NATIVE_ENGINE_DESCRIPTORS.keySet());

        assertEquals(INITIALIZER_NATIVE_METHODS, nativeMethodNames(initializer));
        assertEquals(NATIVE_ENGINE_DESCRIPTORS.keySet(), nativeMethodNames(nativeEngine));
        assertEquals(nativeMethods, nativeMethodNames(rules));
        for (Map.Entry<String, String> method : NATIVE_ENGINE_DESCRIPTORS.entrySet()) {
            assertEquals(
                    1,
                    occurrences(rules, NATIVE_ENGINE_KEEP_SIGNATURES.get(method.getKey())),
                    method.getKey() + " " + method.getValue());
        }
        for (Map.Entry<String, String> method : INITIALIZER_KEEP_SIGNATURES.entrySet()) {
            assertEquals(1, occurrences(rules, method.getValue()), method.getKey());
        }
        assertEquals(2, occurrences(rules, "-keepnames class games.cafecito.foundry.java."));
        assertFalse(rules.contains("native <methods>"));
        assertFalse(rules.contains("games.cafecito.foundry.**"));
        assertFalse(rules.contains("-keep class *"));
        assertFalse(rules.contains("-keep class **"));
    }

    @Test
    void jniBoundaryDoesNotDiscoverGeneratedClassesOrUseReflection() throws IOException {
        String jni = read("foundry-java-android/src/main/cpp/foundry_java_jni.cpp");
        String nativeEngine =
                read(
                        "foundry-java-android/src/main/java/games/cafecito/foundry/java/"
                                + "FoundryNativeEngine.java");
        String boundary = jni + nativeEngine;

        assertFalse(boundary.contains("Class.forName"));
        assertFalse(boundary.contains("getDeclaredMethod"));
        assertFalse(boundary.contains("java/lang/reflect"));
        assertFalse(jni.contains("games/cafecito/foundry/generated"));
        assertFalse(jni.contains("GeneratedNativeDispatch"));
        assertFalse(jni.contains("Ljava/lang/Object;"));
    }

    @Test
    void androidBuildAndVerifierRequireTheExactFourAbiBridge() throws IOException {
        String androidBuild = read("foundry-java-android/build.gradle.kts");
        String verifier = read("gradle/verify-native-bridge.sh");
        String workflow = read(".github/workflows/ci.yml");

        assertEquals(Set.copyOf(JNI_SYMBOLS), verifiedJniSymbols(verifier));
        for (String abi : List.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
            assertTrue(androidBuild.contains("\"" + abi + "\""), abi);
            assertTrue(verifier.contains(abi), abi);
        }
        assertTrue(androidBuild.contains("externalNativeBuild"));
        assertTrue(androidBuild.contains("29.0.14206865"));
        assertTrue(androidBuild.contains("targetSdk = 36"));
        assertTrue(androidBuild.contains("inputs.file(nativeTestScript)"));
        assertTrue(verifier.contains("llvm-readelf"));
        assertTrue(verifier.contains("libfoundry_java.so"));
        assertTrue(verifier.contains("libfoundry_android.so"));
        assertTrue(verifier.contains("grep -Eq '^Java_'"));
        assertTrue(workflow.contains("ndk;29.0.14206865"));
        assertTrue(workflow.contains("verify-native-bridge.sh"));
        assertTrue(workflow.contains("system-images;android-36;default;x86_64"));
        assertTrue(workflow.contains("sudo chmod 666 /dev/kvm"));
        assertTrue(workflow.contains("-port 5554"));
        assertTrue(workflow.contains("sys.boot_completed"));
        assertTrue(workflow.contains(":foundry-java-android:connectedDebugAndroidTest"));
    }

    @Test
    void javaInitializerPinsTheVersionedBootstrapContract() throws IOException {
        String initializer =
                read(
                        "foundry-java-android/src/main/java/games/cafecito/foundry/java/"
                                + "FoundryJavaInitializer.java");

        assertTrue(initializer.contains("FoundryRuntime.API_SHA256"));
        assertTrue(initializer.contains("FoundryRuntime.GENERATOR_VERSION"));
        assertTrue(initializer.contains("FoundryRuntime.RUNTIME_CONTRACT_VERSION"));
        assertTrue(initializer.contains("FoundryRuntime.BRIDGE_CONTRACT_VERSION"));
        assertTrue(initializer.contains("FoundryJavaInitializer.class.getClassLoader()"));
        assertTrue(initializer.contains("FoundryBridgeCallbacks callbacks"));
        assertTrue(initializer.contains("nativeBootstrapV1("));
    }

    @Test
    void nativeContractIsGeneratedFromTheAuthoritativeJavaProvenance() throws IOException {
        String cmake = read("foundry-java-android/src/main/cpp/CMakeLists.txt");
        String normalizedCmake = cmake.replaceAll("\\s+", " ");
        String jni = read("foundry-java-android/src/main/cpp/foundry_java_jni.cpp");
        String contract = read("foundry-java-android/src/main/cpp/foundry_java_contract.h.in");

        assertTrue(cmake.contains("/provenance.json"));
        assertTrue(
                cmake.contains(
                        "foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/"
                                + "FoundryRuntime.java"));
        assertTrue(normalizedCmake.contains("string( JSON FOUNDRY_JAVA_API_SHA256"));
        assertTrue(cmake.contains("configure_file("));
        assertTrue(jni.contains("#include \"foundry_java_contract.h\""));
        assertFalse(jni.contains("constexpr char API_SHA256[]"));
        assertTrue(contract.contains("@FOUNDRY_JAVA_API_SHA256@"));
        assertTrue(contract.contains("@FOUNDRY_JAVA_GENERATOR_VERSION@"));
        assertTrue(contract.contains("@FOUNDRY_JAVA_RUNTIME_VERSION@"));
        assertTrue(contract.contains("@FOUNDRY_JAVA_BRIDGE_CONTRACT_VERSION@"));
    }

    @Test
    void jniFailureAndNoexceptPathsContainEveryException() throws IOException {
        String jni = read("foundry-java-android/src/main/cpp/foundry_java_jni.cpp");
        String handles = read("foundry-java-android/src/main/cpp/foundry_java_handles.cpp");

        assertFalse(
                java.util.regex.Pattern.compile(
                                "==\\s*nullptr\\s*\\|\\|\\s*"
                                        + "(?:foundry_java::)?clear_java_exception")
                        .matcher(jni)
                        .find());
        assertTrue(jni.contains("jni_reference_failed("));
        assertTrue(
                jni.contains("ContextHandle jni_bridge_create_context() noexcept {\n" + "\ttry {"));
        assertFalse(handles.contains("thread_local std::vector<ContextHandle>"));
        assertFalse(handles.contains("std::vector<ContextHandle> handles"));
    }

    @Test
    void bootstrapDoesNotHoldStateMutexAcrossJniWork() throws IOException {
        String jni = read("foundry-java-android/src/main/cpp/foundry_java_jni.cpp");
        int bootstrapStart = jni.indexOf("FoundryJavaInitializer_nativeBootstrapV1(");
        int bootstrapEnd = jni.indexOf("FoundryJavaInitializer_nativeCreateContextV1(");
        String bootstrap = jni.substring(bootstrapStart, bootstrapEnd);

        assertTrue(jni.contains("bootstrap_in_progress"));
        assertTrue(bootstrap.contains("BootstrapReservation"));
        assertFalse(bootstrap.contains("std::lock_guard lock(foundry_java::state.mutex)"));
        assertEquals(2, occurrences(jni, "\"argument unmarshaling\""));
        assertFalse(jni.contains("void release_class_loader()"));
        assertTrue(jni.contains("class_loader = std::exchange(state.class_loader, nullptr)"));
    }

    @Test
    void noSourceOrBuildInputImportsTheAndroidHostRuntime() throws IOException {
        String tree =
                readTree("foundry-java-android/src")
                        + read("foundry-java-android/build.gradle.kts");

        assertFalse(tree.contains("Java_games_cafecito_foundry_FoundryLib"));
        assertFalse(tree.contains("org.godotengine"));
        assertFalse(tree.contains("games.cafecito.foundry.FoundryLib"));
        assertFalse(tree.contains("libfoundry_android.so"));
    }

    private static String read(String relativePath) throws IOException {
        Path path = ROOT.resolve(relativePath);
        assertTrue(Files.isRegularFile(path), relativePath + " must exist");
        return Files.readString(path);
    }

    private static String readTree(String relativePath) throws IOException {
        try (var paths = Files.walk(ROOT.resolve(relativePath))) {
            return paths.filter(Files::isRegularFile)
                    .map(
                            path -> {
                                try {
                                    return Files.readString(path);
                                } catch (IOException exception) {
                                    throw new IllegalStateException(exception);
                                }
                            })
                    .reduce("", String::concat);
        }
    }

    private static int occurrences(String value, String needle) {
        return value.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static Set<String> nativeMethodNames(String source) {
        Matcher matcher =
                Pattern.compile(
                                "private\\s+static\\s+native\\s+[\\w.$<>?\\[\\]]+\\s+"
                                        + "(native\\w+V1)\\s*\\(")
                        .matcher(source);
        var names = new java.util.HashSet<String>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return Set.copyOf(names);
    }

    private static Set<String> exportedJniSymbols(String exports) {
        return matchingValues(exports, Pattern.compile("\\b(Java_[A-Za-z0-9_]+);"));
    }

    private static Set<String> verifiedJniSymbols(String verifier) {
        return matchingValues(verifier, Pattern.compile("'(Java_[A-Za-z0-9_]+)'"));
    }

    private static Set<String> matchingValues(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        var matches = new java.util.HashSet<String>();
        while (matcher.find()) {
            matches.add(matcher.group(1));
        }
        return Set.copyOf(matches);
    }
}
