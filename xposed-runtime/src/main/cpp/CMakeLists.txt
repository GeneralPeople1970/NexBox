cmake_minimum_required(VERSION 3.31.6)
project(nexbox_xposed LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(DEBUG_SYMBOLS_PATH "${CMAKE_CURRENT_BINARY_DIR}/symbols")
set(LSPLANT_BUILD_SHARED ON CACHE BOOL "Build replaceable LGPL shared library" FORCE)
# NexBox vendors runtime headers/sources, not upstream test and example projects.
add_subdirectory(third_party/lsplant)

if(NOT DEFINED NEXBOX_CORE_DIR)
    message(FATAL_ERROR "NEXBOX_CORE_DIR must point to Bcore/src/main/cpp")
endif()
add_library(nexbox_dobby STATIC IMPORTED)
set_target_properties(nexbox_dobby PROPERTIES
    IMPORTED_LOCATION "${NEXBOX_CORE_DIR}/Dobby/${ANDROID_ABI}/libdobby.a")
add_library(nexbox_xdl STATIC
    third_party/xdl/xdl.c third_party/xdl/xdl_iterate.c
    third_party/xdl/xdl_linker.c third_party/xdl/xdl_lzma.c
    third_party/xdl/xdl_util.c)
target_include_directories(nexbox_xdl PUBLIC third_party/xdl)
target_compile_options(nexbox_xdl PRIVATE -fvisibility=hidden)

add_library(nexbox_xposed SHARED runtime.cpp)
target_include_directories(nexbox_xposed PRIVATE "${NEXBOX_CORE_DIR}/Dobby")
target_link_libraries(nexbox_xposed PRIVATE lsplant nexbox_dobby nexbox_xdl log android z)
target_compile_options(nexbox_xposed PRIVATE -fvisibility=hidden)
# JNI methods are registered by address; only the VM entry point needs name lookup.
set(NEXBOX_XPOSED_VERSION_SCRIPT "${CMAKE_CURRENT_SOURCE_DIR}/nexbox_xposed.map.txt")
target_link_options(nexbox_xposed PRIVATE
    "-Wl,--version-script=${NEXBOX_XPOSED_VERSION_SCRIPT}"
    -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
set_property(TARGET nexbox_xposed APPEND PROPERTY LINK_DEPENDS "${NEXBOX_XPOSED_VERSION_SCRIPT}")
target_link_options(lsplant PRIVATE -Wl,-z,max-page-size=16384)
