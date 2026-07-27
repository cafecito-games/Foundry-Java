cmake_policy(PUSH)
cmake_policy(SET CMP0057 NEW)

if(NOT DEFINED FOUNDRY_JAVA_API_JSON OR
        NOT DEFINED FOUNDRY_JAVA_PROVENANCE OR
        NOT DEFINED FOUNDRY_JAVA_ABI_TEMPLATE OR
        NOT DEFINED FOUNDRY_JAVA_ABI_OUTPUT)
    message(FATAL_ERROR "Native ABI layout generation requires API, provenance, template, and output paths.")
endif()
if(NOT DEFINED FOUNDRY_JAVA_BRIDGE_PRECISION)
    set(FOUNDRY_JAVA_BRIDGE_PRECISION "float")
endif()
if(NOT FOUNDRY_JAVA_BRIDGE_PRECISION STREQUAL "float")
    message(FATAL_ERROR "Foundry Java bridge precision must be exactly 'float'.")
endif()

file(SHA256 "${FOUNDRY_JAVA_API_JSON}" FOUNDRY_JAVA_ACTUAL_API_SHA256)
file(READ "${FOUNDRY_JAVA_PROVENANCE}" FOUNDRY_JAVA_PROVENANCE_JSON)
string(
    JSON FOUNDRY_JAVA_EXPECTED_API_SHA256
    ERROR_VARIABLE FOUNDRY_JAVA_PROVENANCE_ERROR
    GET "${FOUNDRY_JAVA_PROVENANCE_JSON}" files extension_api_json sha256
)
if(NOT FOUNDRY_JAVA_PROVENANCE_ERROR STREQUAL "NOTFOUND")
    message(FATAL_ERROR "Malformed provenance: ${FOUNDRY_JAVA_PROVENANCE_ERROR}")
endif()
if(NOT FOUNDRY_JAVA_ACTUAL_API_SHA256 STREQUAL FOUNDRY_JAVA_EXPECTED_API_SHA256)
    message(
        FATAL_ERROR
        "extension_api.json SHA-256 ${FOUNDRY_JAVA_ACTUAL_API_SHA256} does not match provenance "
        "${FOUNDRY_JAVA_EXPECTED_API_SHA256}."
    )
endif()

file(READ "${FOUNDRY_JAVA_API_JSON}" FOUNDRY_JAVA_API)
string(
    JSON FOUNDRY_JAVA_CONFIGURATION_COUNT
    ERROR_VARIABLE FOUNDRY_JAVA_API_ERROR
    LENGTH "${FOUNDRY_JAVA_API}" builtin_class_sizes
)
if(NOT FOUNDRY_JAVA_API_ERROR STREQUAL "NOTFOUND")
    message(FATAL_ERROR "Malformed builtin_class_sizes: ${FOUNDRY_JAVA_API_ERROR}")
endif()

set(FOUNDRY_JAVA_FLOAT_32_INDEX "")
set(FOUNDRY_JAVA_FLOAT_64_INDEX "")
math(EXPR FOUNDRY_JAVA_CONFIGURATION_LAST "${FOUNDRY_JAVA_CONFIGURATION_COUNT} - 1")
foreach(FOUNDRY_JAVA_CONFIGURATION_INDEX RANGE 0 ${FOUNDRY_JAVA_CONFIGURATION_LAST})
    string(
        JSON FOUNDRY_JAVA_CONFIGURATION_NAME
        GET "${FOUNDRY_JAVA_API}" builtin_class_sizes ${FOUNDRY_JAVA_CONFIGURATION_INDEX} build_configuration
    )
    if(FOUNDRY_JAVA_CONFIGURATION_NAME STREQUAL "float_32")
        if(NOT FOUNDRY_JAVA_FLOAT_32_INDEX STREQUAL "")
            message(FATAL_ERROR "Duplicate float_32 builtin-class layout.")
        endif()
        set(FOUNDRY_JAVA_FLOAT_32_INDEX "${FOUNDRY_JAVA_CONFIGURATION_INDEX}")
    elseif(FOUNDRY_JAVA_CONFIGURATION_NAME STREQUAL "float_64")
        if(NOT FOUNDRY_JAVA_FLOAT_64_INDEX STREQUAL "")
            message(FATAL_ERROR "Duplicate float_64 builtin-class layout.")
        endif()
        set(FOUNDRY_JAVA_FLOAT_64_INDEX "${FOUNDRY_JAVA_CONFIGURATION_INDEX}")
    endif()
endforeach()
if(FOUNDRY_JAVA_FLOAT_32_INDEX STREQUAL "" OR FOUNDRY_JAVA_FLOAT_64_INDEX STREQUAL "")
    message(FATAL_ERROR "Both float_32 and float_64 builtin-class layouts are required.")
endif()

set(FOUNDRY_JAVA_LAYOUT_NAMES "")
foreach(FOUNDRY_JAVA_LAYOUT_BITS IN ITEMS 32 64)
    set(FOUNDRY_JAVA_LAYOUT_INDEX "${FOUNDRY_JAVA_FLOAT_${FOUNDRY_JAVA_LAYOUT_BITS}_INDEX}")
    string(
        JSON FOUNDRY_JAVA_LAYOUT_COUNT
        LENGTH "${FOUNDRY_JAVA_API}" builtin_class_sizes ${FOUNDRY_JAVA_LAYOUT_INDEX} sizes
    )
    if(NOT FOUNDRY_JAVA_LAYOUT_COUNT EQUAL 40)
        message(FATAL_ERROR "float_${FOUNDRY_JAVA_LAYOUT_BITS} must contain exactly 40 layout rows.")
    endif()

    set(FOUNDRY_JAVA_SEEN_NAMES "")
    set(FOUNDRY_JAVA_FLOAT_${FOUNDRY_JAVA_LAYOUT_BITS}_ROWS "")
    foreach(FOUNDRY_JAVA_ROW_INDEX RANGE 0 39)
        string(
            JSON FOUNDRY_JAVA_ROW_NAME
            GET "${FOUNDRY_JAVA_API}" builtin_class_sizes ${FOUNDRY_JAVA_LAYOUT_INDEX} sizes
                ${FOUNDRY_JAVA_ROW_INDEX} name
        )
        string(
            JSON FOUNDRY_JAVA_ROW_SIZE
            GET "${FOUNDRY_JAVA_API}" builtin_class_sizes ${FOUNDRY_JAVA_LAYOUT_INDEX} sizes
                ${FOUNDRY_JAVA_ROW_INDEX} size
        )
        if(FOUNDRY_JAVA_ROW_NAME IN_LIST FOUNDRY_JAVA_SEEN_NAMES)
            message(FATAL_ERROR "float_${FOUNDRY_JAVA_LAYOUT_BITS} contains duplicate name ${FOUNDRY_JAVA_ROW_NAME}.")
        endif()
        list(APPEND FOUNDRY_JAVA_SEEN_NAMES "${FOUNDRY_JAVA_ROW_NAME}")
        if(NOT FOUNDRY_JAVA_ROW_SIZE MATCHES "^[0-9]+$")
            message(FATAL_ERROR "Layout size for ${FOUNDRY_JAVA_ROW_NAME} must be a nonnegative integer.")
        endif()
        if(FOUNDRY_JAVA_ROW_INDEX EQUAL 0)
            if(NOT FOUNDRY_JAVA_ROW_NAME STREQUAL "Nil" OR NOT FOUNDRY_JAVA_ROW_SIZE EQUAL 0)
                message(FATAL_ERROR "Nil must be the first layout row with size zero.")
            endif()
        elseif(FOUNDRY_JAVA_ROW_SIZE LESS_EQUAL 0)
            message(FATAL_ERROR "Layout size for ${FOUNDRY_JAVA_ROW_NAME} must be positive.")
        endif()

        if(FOUNDRY_JAVA_LAYOUT_BITS EQUAL 32)
            list(APPEND FOUNDRY_JAVA_LAYOUT_NAMES "${FOUNDRY_JAVA_ROW_NAME}")
        else()
            list(GET FOUNDRY_JAVA_LAYOUT_NAMES ${FOUNDRY_JAVA_ROW_INDEX} FOUNDRY_JAVA_EXPECTED_NAME)
            if(NOT FOUNDRY_JAVA_ROW_NAME STREQUAL FOUNDRY_JAVA_EXPECTED_NAME)
                message(
                    FATAL_ERROR
                    "float_64 row ${FOUNDRY_JAVA_ROW_INDEX} is ${FOUNDRY_JAVA_ROW_NAME}; "
                    "expected ${FOUNDRY_JAVA_EXPECTED_NAME}."
                )
            endif()
        endif()
        string(
            APPEND FOUNDRY_JAVA_FLOAT_${FOUNDRY_JAVA_LAYOUT_BITS}_ROWS
            "        { \"${FOUNDRY_JAVA_ROW_NAME}\", ${FOUNDRY_JAVA_ROW_SIZE} },\n"
        )
    endforeach()

    foreach(FOUNDRY_JAVA_SENTINEL IN ITEMS String StringName Object Variant)
        list(FIND FOUNDRY_JAVA_SEEN_NAMES "${FOUNDRY_JAVA_SENTINEL}" FOUNDRY_JAVA_SENTINEL_INDEX)
        string(
            JSON FOUNDRY_JAVA_SENTINEL_SIZE
            GET "${FOUNDRY_JAVA_API}" builtin_class_sizes ${FOUNDRY_JAVA_LAYOUT_INDEX} sizes
                ${FOUNDRY_JAVA_SENTINEL_INDEX} size
        )
        if(FOUNDRY_JAVA_SENTINEL STREQUAL "Variant")
            set(FOUNDRY_JAVA_EXPECTED_SENTINEL_SIZE 24)
        elseif(FOUNDRY_JAVA_LAYOUT_BITS EQUAL 32)
            set(FOUNDRY_JAVA_EXPECTED_SENTINEL_SIZE 4)
        else()
            set(FOUNDRY_JAVA_EXPECTED_SENTINEL_SIZE 8)
        endif()
        if(NOT FOUNDRY_JAVA_SENTINEL_SIZE EQUAL FOUNDRY_JAVA_EXPECTED_SENTINEL_SIZE)
            message(
                FATAL_ERROR
                "float_${FOUNDRY_JAVA_LAYOUT_BITS} ${FOUNDRY_JAVA_SENTINEL} size must be "
                "${FOUNDRY_JAVA_EXPECTED_SENTINEL_SIZE}."
            )
        endif()
    endforeach()
endforeach()

get_filename_component(FOUNDRY_JAVA_ABI_OUTPUT_DIRECTORY "${FOUNDRY_JAVA_ABI_OUTPUT}" DIRECTORY)
file(MAKE_DIRECTORY "${FOUNDRY_JAVA_ABI_OUTPUT_DIRECTORY}")
configure_file("${FOUNDRY_JAVA_ABI_TEMPLATE}" "${FOUNDRY_JAVA_ABI_OUTPUT}" @ONLY)

cmake_policy(POP)
