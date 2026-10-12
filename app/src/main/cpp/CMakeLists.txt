# Sceau - native library libsceau_jp2.so: JPEG 2000 decoding (DG2 portrait, DG12).
# OpenJPEG is compiled from the sources of the openjpeg/ submodule (no prebuilt binary,
# an F-Droid requirement) and linked statically. See docs/decisions.md (D3).
cmake_minimum_required(VERSION 3.22.1)
project(sceau_jp2 C)

# Symbols hidden by default, including OpenJPEG's: only the JNI function is exported.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# OpenJPEG: only the openjp2 library, static, without codecs, tools or tests.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(BUILD_CODEC OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOC OFF CACHE BOOL "" FORCE)
set(BUILD_JPIP OFF CACHE BOOL "" FORCE)
set(BUILD_JAVA OFF CACHE BOOL "" FORCE)
set(BUILD_VIEWER OFF CACHE BOOL "" FORCE)
set(BUILD_LUTS_GENERATOR OFF CACHE BOOL "" FORCE)
set(BUILD_UNIT_TESTS OFF CACHE BOOL "" FORCE)
# Single-threaded decoding: portraits are small, and this avoids pthread and its shared buffers.
set(OPJ_USE_THREAD OFF CACHE BOOL "" FORCE)

# Reproducible build (D25): the absolute paths of the clone, the build directory and the NDK,
# present in the debug information (__FILE__, DW_AT_comp_dir, sysroot headers), are replaced
# with fixed names. Without this, the build-id computed by the linker before stripping changes
# with the location of the clone or the SDK. Placed before add_subdirectory so that it also
# applies to OpenJPEG.
set(SCEAU_NDK_DIR "${ANDROID_NDK}")
if(NOT SCEAU_NDK_DIR)
    set(SCEAU_NDK_DIR "${CMAKE_ANDROID_NDK}")
endif()
add_compile_options(
    "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=src"
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=build")
if(SCEAU_NDK_DIR)
    add_compile_options("-ffile-prefix-map=${SCEAU_NDK_DIR}=ndk")
endif()

if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/openjpeg/CMakeLists.txt")
    message(FATAL_ERROR
        "OpenJPEG sources missing: run 'git submodule update --init' at the root of the repository.")
endif()
add_subdirectory(openjpeg EXCLUDE_FROM_ALL)

# Capped allocator: opj_malloc.c is removed from openjp2 and replaced by jp2_alloc.c, linked into
# libsceau_jp2.so, which enforces the memory budget of each decoding (decompression bomb).
get_target_property(SCEAU_OPJ_SOURCES openjp2 SOURCES)
list(FILTER SCEAU_OPJ_SOURCES EXCLUDE REGEX "/opj_malloc\\.c$")
set_property(TARGET openjp2 PROPERTY SOURCES ${SCEAU_OPJ_SOURCES})

# OPJ_STATIC: the OpenJPEG API is declared "hidden" instead of being exported by the .so.
target_compile_definitions(openjp2 PUBLIC OPJ_STATIC)
# Third-party code: its warnings are not ours and must not clutter the build.
# They remain enabled (-Wall -Wextra -Werror) for Sceau's code below.
target_compile_options(openjp2 PRIVATE -w)

# Hardening, applied to OpenJPEG as well as to Sceau's code.
set(SCEAU_HARDENING_FLAGS -fstack-protector-strong -ffunction-sections -fdata-sections)
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    # _FORTIFY_SOURCE only takes effect with optimization (already defined to 2 by the NDK).
    list(APPEND SCEAU_HARDENING_FLAGS -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=2)
endif()
target_compile_options(openjp2 PRIVATE ${SCEAU_HARDENING_FLAGS})

add_library(sceau_jp2 SHARED jp2_decode.c jp2_alloc.c jp2_jni.c)
target_compile_options(sceau_jp2 PRIVATE
    -std=c11 -Wall -Wextra -Wconversion -Wsign-conversion -Wshadow -Wstrict-prototypes
    -Werror ${SCEAU_HARDENING_FLAGS})
target_include_directories(sceau_jp2 PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/openjpeg/src/lib/openjp2"
    "${CMAKE_CURRENT_BINARY_DIR}/openjpeg/src/lib/openjp2")
target_link_libraries(sceau_jp2 PRIVATE openjp2)
target_link_options(sceau_jp2 PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,relro
    -Wl,-z,now
    -Wl,-z,noexecstack
    # 16 KB pages (Android 15+): the NDK r28 default, made explicit.
    -Wl,-z,max-page-size=16384)
