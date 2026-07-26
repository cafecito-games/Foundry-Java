package games.cafecito.foundry.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NativeBridgeContractTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final String ENTRY_SYMBOL = "foundry_java_library_init";
    private static final Set<String> JNI_SYMBOLS =
            Set.of(
                    "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeBootstrapV1",
                    "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeCreateContextV1",
                    "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeInvokeCallbackV1",
                    "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeInvokeCallbackOnThreadV1",
                    "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeShutdownContextV1",
                    "Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeShutdownBridgeV1");
    private static final List<String> BRIDGE_FILES =
            List.of(
                    "foundry-java-android/src/main/cpp/CMakeLists.txt",
                    "foundry-java-android/src/main/cpp/foundry_java_entry.cpp",
                    "foundry-java-android/src/main/cpp/foundry_java_jni.cpp",
                    "foundry-java-android/src/main/cpp/foundry_java_contract.h.in",
                    "foundry-java-android/src/main/cpp/foundry_java_runtime.h",
                    "foundry-java-android/src/main/cpp/foundry_java_handles.cpp",
                    "foundry-java-android/src/main/cpp/foundry_java_exports.map",
                    "foundry-java-android/src/main/java/games/cafecito/foundry/java/FoundryJavaInitializer.java",
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
        for (String symbol : JNI_SYMBOLS) {
            assertEquals(1, occurrences(exports, symbol + ";"), symbol);
            assertTrue(jni.contains(symbol), symbol);
        }
    }

    @Test
    void androidBuildAndVerifierRequireTheExactFourAbiBridge() throws IOException {
        String androidBuild = read("foundry-java-android/build.gradle.kts");
        String verifier = read("gradle/verify-native-bridge.sh");
        String workflow = read(".github/workflows/ci.yml");

        for (String abi : List.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
            assertTrue(androidBuild.contains("\"" + abi + "\""), abi);
            assertTrue(verifier.contains(abi), abi);
        }
        assertTrue(androidBuild.contains("externalNativeBuild"));
        assertTrue(androidBuild.contains("29.0.14206865"));
        assertTrue(verifier.contains("llvm-readelf"));
        assertTrue(verifier.contains("libfoundry_java.so"));
        assertTrue(verifier.contains("libfoundry_android.so"));
        assertTrue(workflow.contains("ndk;29.0.14206865"));
        assertTrue(workflow.contains("verify-native-bridge.sh"));
        assertTrue(workflow.contains("system-images;android-36;default;x86_64"));
        assertTrue(workflow.contains("sudo chmod 666 /dev/kvm"));
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
}
