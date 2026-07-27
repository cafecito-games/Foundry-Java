cmake_minimum_required(VERSION 3.22.1)

project(foundry_java_bridge LANGUAGES CXX)

find_package(Threads REQUIRED)

option(FOUNDRY_JAVA_BUILD_TESTS "Build Foundry Java native unit tests." OFF)
option(FOUNDRY_JAVA_BUILD_ANDROID_TEST_HOST "Build the Android instrumentation test host." OFF)
option(FOUNDRY_JAVA_ENABLE_SANITIZERS "Enable address and undefined behavior sanitizers." OFF)

set(FOUNDRY_JAVA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")
set(FOUNDRY_JAVA_PUBLIC_API "${FOUNDRY_JAVA_ROOT}/api/current")
set(FOUNDRY_JAVA_PROVENANCE "${FOUNDRY_JAVA_PUBLIC_API}/provenance.json")
set(
    FOUNDRY_JAVA_RUNTIME_SOURCE
    "${FOUNDRY_JAVA_ROOT}/foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryRuntime.java"
)

file(READ "${FOUNDRY_JAVA_PROVENANCE}" FOUNDRY_JAVA_PROVENANCE_JSON)
string(
    JSON FOUNDRY_JAVA_API_SHA256
    GET "${FOUNDRY_JAVA_PROVENANCE_JSON}" files extension_api_json sha256
)
string(
    JSON FOUNDRY_JAVA_GENERATOR_VERSION
    GET "${FOUNDRY_JAVA_PROVENANCE_JSON}" generator_version
)
string(
    JSON FOUNDRY_JAVA_BRIDGE_CONTRACT_VERSION
    GET "${FOUNDRY_JAVA_PROVENANCE_JSON}" bridge_contract_version
)
file(READ "${FOUNDRY_JAVA_RUNTIME_SOURCE}" FOUNDRY_JAVA_RUNTIME_JAVA)
string(
    REGEX MATCH
    "RUNTIME_CONTRACT_VERSION[^\"]*\"([^\"]+)\""
    FOUNDRY_JAVA_RUNTIME_VERSION_MATCH
    "${FOUNDRY_JAVA_RUNTIME_JAVA}"
)
set(FOUNDRY_JAVA_RUNTIME_VERSION "${CMAKE_MATCH_1}")
if(FOUNDRY_JAVA_RUNTIME_VERSION STREQUAL "")
    message(FATAL_ERROR "Could not read RUNTIME_CONTRACT_VERSION from FoundryRuntime.java.")
endif()
set(FOUNDRY_JAVA_GENERATED_INCLUDE "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${FOUNDRY_JAVA_GENERATED_INCLUDE}")
configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/foundry_java_contract.h.in"
    "${FOUNDRY_JAVA_GENERATED_INCLUDE}/foundry_java_contract.h"
    @ONLY
)
set(FOUNDRY_JAVA_ABI_GENERATOR "${CMAKE_CURRENT_SOURCE_DIR}/cmake/GenerateFoundryJavaAbiLayout.cmake")
set(FOUNDRY_JAVA_ABI_TEMPLATE "${CMAKE_CURRENT_SOURCE_DIR}/foundry_java_abi_layout.h.in")
set(FOUNDRY_JAVA_API_JSON "${FOUNDRY_JAVA_PUBLIC_API}/extension_api.json")
set(FOUNDRY_JAVA_ABI_OUTPUT "${FOUNDRY_JAVA_GENERATED_INCLUDE}/foundry_java_abi_layout.h")
set(FOUNDRY_JAVA_BRIDGE_PRECISION "float")
set_property(
    DIRECTORY
    APPEND
    PROPERTY CMAKE_CONFIGURE_DEPENDS
        "${FOUNDRY_JAVA_ABI_GENERATOR}"
        "${FOUNDRY_JAVA_ABI_TEMPLATE}"
        "${FOUNDRY_JAVA_API_JSON}"
        "${FOUNDRY_JAVA_PROVENANCE}"
)
include("${FOUNDRY_JAVA_ABI_GENERATOR}")

add_library(
    foundry_java_runtime
    STATIC
        foundry_java_handles.cpp
        foundry_java_interface.cpp
        foundry_java_registration.cpp
        foundry_java_registration_bridge.cpp
        foundry_java_transport.cpp
)
target_compile_features(foundry_java_runtime PUBLIC cxx_std_17)
target_include_directories(
    foundry_java_runtime
    PUBLIC
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${FOUNDRY_JAVA_PUBLIC_API}"
        "${FOUNDRY_JAVA_GENERATED_INCLUDE}"
)
target_compile_options(foundry_java_runtime PRIVATE -Wall -Wextra -Werror)
set_target_properties(foundry_java_runtime PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
    add_library(foundry_java SHARED
        foundry_java_entry.cpp
        foundry_java_jni.cpp
    )
    target_link_libraries(foundry_java PRIVATE foundry_java_runtime Threads::Threads)
    target_include_directories(foundry_java PRIVATE "${FOUNDRY_JAVA_GENERATED_INCLUDE}")
    target_compile_features(foundry_java PRIVATE cxx_std_17)
    target_compile_options(foundry_java PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
    target_link_options(
        foundry_java
        PRIVATE
            "-Wl,--no-undefined"
            "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/foundry_java_exports.map"
    )

    if(FOUNDRY_JAVA_BUILD_ANDROID_TEST_HOST AND CMAKE_BUILD_TYPE STREQUAL "Debug")
        add_library(
            foundry_java_test_host
            SHARED
                "${FOUNDRY_JAVA_ROOT}/foundry-java-android/src/androidTest/cpp/foundry_java_fake_extension_host.cpp"
                "${FOUNDRY_JAVA_ROOT}/foundry-java-android/src/androidTest/cpp/foundry_java_fake_extension_host.h"
                "${FOUNDRY_JAVA_ROOT}/foundry-java-android/src/androidTest/cpp/foundry_java_test_host_jni.cpp"
        )
        target_link_libraries(foundry_java_test_host PRIVATE foundry_java Threads::Threads)
        target_include_directories(
            foundry_java_test_host
            PRIVATE
                "${FOUNDRY_JAVA_PUBLIC_API}"
                "${FOUNDRY_JAVA_ROOT}/foundry-java-android/src/androidTest/cpp"
        )
        target_compile_features(foundry_java_test_host PRIVATE cxx_std_17)
        target_compile_options(foundry_java_test_host PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
        target_link_options(foundry_java_test_host PRIVATE "-Wl,--no-undefined")
    endif()
endif()

if(FOUNDRY_JAVA_BUILD_TESTS)
    enable_testing()
    find_package(JNI REQUIRED)
    target_compile_definitions(foundry_java_runtime PRIVATE FOUNDRY_JAVA_TESTING)
    add_executable(
        foundry_java_registration_test
        foundry_java_jni.cpp
        "${FOUNDRY_JAVA_ROOT}/foundry-java-android/src/test/cpp/foundry_java_registration_test.cpp"
    )
    target_link_libraries(foundry_java_registration_test PRIVATE foundry_java_runtime Threads::Threads)
    target_compile_definitions(foundry_java_registration_test PRIVATE FOUNDRY_JAVA_TESTING)
    target_include_directories(foundry_java_registration_test PRIVATE ${JNI_INCLUDE_DIRS})
    target_compile_features(foundry_java_registration_test PRIVATE cxx_std_17)
    target_compile_options(foundry_java_registration_test PRIVATE -Wall -Wextra -Werror)
    add_executable(
        foundry_java_runtime_test
        foundry_java_entry.cpp
        "${FOUNDRY_JAVA_ROOT}/foundry-java-android/src/test/cpp/foundry_java_runtime_test.cpp"
    )
    target_link_libraries(foundry_java_runtime_test PRIVATE foundry_java_runtime Threads::Threads)
    target_compile_definitions(foundry_java_runtime_test PRIVATE FOUNDRY_JAVA_TESTING)
    target_compile_features(foundry_java_runtime_test PRIVATE cxx_std_17)
    target_compile_options(foundry_java_runtime_test PRIVATE -Wall -Wextra -Werror)
    add_executable(
        foundry_java_fake_extension_host_test
        "${FOUNDRY_JAVA_ROOT}/foundry-java-android/src/androidTest/cpp/foundry_java_fake_extension_host.cpp"
    )
    target_link_libraries(foundry_java_fake_extension_host_test PRIVATE Threads::Threads)
    target_include_directories(foundry_java_fake_extension_host_test PRIVATE "${FOUNDRY_JAVA_PUBLIC_API}")
    target_compile_definitions(
        foundry_java_fake_extension_host_test
        PRIVATE FOUNDRY_JAVA_FAKE_HOST_CONTRACT_TEST
    )
    target_compile_features(foundry_java_fake_extension_host_test PRIVATE cxx_std_17)
    target_compile_options(foundry_java_fake_extension_host_test PRIVATE -Wall -Wextra -Werror)
    if(FOUNDRY_JAVA_ENABLE_SANITIZERS)
        target_compile_options(foundry_java_runtime PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_compile_options(foundry_java_registration_test PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_compile_options(foundry_java_runtime_test PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_compile_options(foundry_java_fake_extension_host_test PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_link_options(foundry_java_registration_test PRIVATE -fsanitize=address,undefined)
        target_link_options(foundry_java_runtime_test PRIVATE -fsanitize=address,undefined)
        target_link_options(foundry_java_fake_extension_host_test PRIVATE -fsanitize=address,undefined)
    endif()
    add_test(NAME foundry_java_registration_test COMMAND foundry_java_registration_test)
    add_test(NAME foundry_java_runtime_test COMMAND foundry_java_runtime_test)
    add_test(NAME foundry_java_fake_extension_host_test COMMAND foundry_java_fake_extension_host_test)
endif()
