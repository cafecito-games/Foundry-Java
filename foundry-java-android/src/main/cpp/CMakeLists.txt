cmake_minimum_required(VERSION 3.22.1)

project(foundry_java_bridge LANGUAGES CXX)

find_package(Threads REQUIRED)

option(FOUNDRY_JAVA_BUILD_TESTS "Build Foundry Java native unit tests." OFF)
option(FOUNDRY_JAVA_ENABLE_SANITIZERS "Enable address and undefined behavior sanitizers." OFF)

set(FOUNDRY_JAVA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")
set(FOUNDRY_JAVA_PUBLIC_API "${FOUNDRY_JAVA_ROOT}/api/current")

add_library(foundry_java_runtime STATIC foundry_java_handles.cpp)
target_compile_features(foundry_java_runtime PUBLIC cxx_std_17)
target_include_directories(
    foundry_java_runtime
    PUBLIC
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${FOUNDRY_JAVA_PUBLIC_API}"
)
target_compile_options(foundry_java_runtime PRIVATE -Wall -Wextra -Werror)
set_target_properties(foundry_java_runtime PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
    add_library(foundry_java SHARED
        foundry_java_entry.cpp
        foundry_java_jni.cpp
    )
    target_link_libraries(foundry_java PRIVATE foundry_java_runtime Threads::Threads)
    target_compile_features(foundry_java PRIVATE cxx_std_17)
    target_compile_options(foundry_java PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
    target_link_options(
        foundry_java
        PRIVATE
            "-Wl,--no-undefined"
            "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/foundry_java_exports.map"
    )
endif()

if(FOUNDRY_JAVA_BUILD_TESTS)
    enable_testing()
    add_executable(
        foundry_java_runtime_test
        foundry_java_entry.cpp
        "${FOUNDRY_JAVA_ROOT}/foundry-java-android/src/test/cpp/foundry_java_runtime_test.cpp"
    )
    target_link_libraries(foundry_java_runtime_test PRIVATE foundry_java_runtime Threads::Threads)
    target_compile_features(foundry_java_runtime_test PRIVATE cxx_std_17)
    target_compile_options(foundry_java_runtime_test PRIVATE -Wall -Wextra -Werror)
    if(FOUNDRY_JAVA_ENABLE_SANITIZERS)
        target_compile_options(foundry_java_runtime PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_compile_options(foundry_java_runtime_test PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_link_options(foundry_java_runtime_test PRIVATE -fsanitize=address,undefined)
    endif()
    add_test(NAME foundry_java_runtime_test COMMAND foundry_java_runtime_test)
endif()
