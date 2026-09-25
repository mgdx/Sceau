# Sceau - bibliothèque native libsceau_jp2.so : décodage JPEG 2000 (portrait DG2, DG12).
# OpenJPEG est compilé depuis les sources du sous-module openjpeg/ (aucun binaire précompilé,
# exigence F-Droid) et lié statiquement. Voir docs/decisions.md (D3).
cmake_minimum_required(VERSION 3.22.1)
project(sceau_jp2 C)

# Symboles cachés par défaut, y compris ceux d'OpenJPEG : seule la fonction JNI est exportée.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# OpenJPEG : uniquement la bibliothèque openjp2, statique, sans codecs, outils ni tests.
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
# Décodage mono-fil : les portraits sont petits, et on évite pthread et ses tampons partagés.
set(OPJ_USE_THREAD OFF CACHE BOOL "" FORCE)

if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/openjpeg/CMakeLists.txt")
    message(FATAL_ERROR
        "Sources d'OpenJPEG absentes : lancez « git submodule update --init » à la racine du dépôt.")
endif()
add_subdirectory(openjpeg EXCLUDE_FROM_ALL)

# Allocateur plafonné : opj_malloc.c est retiré d'openjp2 et remplacé par jp2_alloc.c, lié dans
# libsceau_jp2.so, qui impose le budget mémoire de chaque décodage (bombe de décompression).
get_target_property(SCEAU_OPJ_SOURCES openjp2 SOURCES)
list(FILTER SCEAU_OPJ_SOURCES EXCLUDE REGEX "/opj_malloc\\.c$")
set_property(TARGET openjp2 PROPERTY SOURCES ${SCEAU_OPJ_SOURCES})

# OPJ_STATIC : l'API OpenJPEG est déclarée « hidden » au lieu d'être exportée par le .so.
target_compile_definitions(openjp2 PUBLIC OPJ_STATIC)
# Code tiers : ses avertissements ne sont pas les nôtres et ne doivent pas polluer la build.
# Ils restent actifs (-Wall -Wextra -Werror) pour le code de Sceau ci-dessous.
target_compile_options(openjp2 PRIVATE -w)

# Durcissement, appliqué à OpenJPEG comme au code de Sceau.
set(SCEAU_HARDENING_FLAGS -fstack-protector-strong -ffunction-sections -fdata-sections)
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    # _FORTIFY_SOURCE n'a d'effet qu'avec l'optimisation (déjà défini à 2 par le NDK).
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
    # Pages de 16 Ko (Android 15+) : défaut du NDK r28, rendu explicite.
    -Wl,-z,max-page-size=16384)
