# Sceau - harnais de test hôte du décodeur JPEG 2000. Hors APK : lancé par run-host-tests.sh.
cmake_minimum_required(VERSION 3.22.1)
project(sceau_jp2_host_test C)

set(SCEAU_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/..")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(BUILD_CODEC OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPJ_USE_THREAD OFF CACHE BOOL "" FORCE)
add_subdirectory("${SCEAU_CPP_DIR}/openjpeg" openjpeg EXCLUDE_FROM_ALL)
target_compile_definitions(openjp2 PUBLIC OPJ_STATIC)
target_compile_options(openjp2 PRIVATE -w)

add_executable(jp2_host_test jp2_host_test.c "${SCEAU_CPP_DIR}/jp2_decode.c")
target_include_directories(jp2_host_test PRIVATE
    "${SCEAU_CPP_DIR}"
    "${SCEAU_CPP_DIR}/openjpeg/src/lib/openjp2"
    "${CMAKE_CURRENT_BINARY_DIR}/openjpeg/src/lib/openjp2")
# Mêmes exigences d'avertissements que pour l'APK, sur le cœur de décodage.
set_source_files_properties("${SCEAU_CPP_DIR}/jp2_decode.c" PROPERTIES COMPILE_OPTIONS
    "-std=c11;-Wall;-Wextra;-Wconversion;-Wsign-conversion;-Wshadow;-Wstrict-prototypes;-Werror")
target_compile_options(jp2_host_test PRIVATE -Wall -Wextra)
target_link_libraries(jp2_host_test PRIVATE openjp2)
# OpenJPEG impose EXECUTABLE_OUTPUT_PATH à son dossier bin/ : on garde le harnais à la racine.
set_target_properties(jp2_host_test PROPERTIES RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}")
