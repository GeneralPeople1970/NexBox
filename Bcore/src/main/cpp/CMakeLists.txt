cmake_minimum_required(VERSION 3.31.6)
project(nexbox LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(nexbox_dobby STATIC IMPORTED)
set_target_properties(nexbox_dobby PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/Dobby/${ANDROID_ABI}/libdobby.a")

add_library(xdl STATIC
    xdl/xdl.c
    xdl/xdl_iterate.c
    xdl/xdl_linker.c
    xdl/xdl_lzma.c
    xdl/xdl_util.c)
target_include_directories(xdl PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
set_target_properties(xdl PROPERTIES STATIC_LIBRARY_OPTIONS "--thin")

add_library(nexbox SHARED
    BoxCore.cpp
    hidden_api.cpp
    IO.cpp
    Utils/elf_util.cpp
    Hook/DexFileHook.cpp
    Hook/FileSystemHook.cpp
    Utils/VirtualSpoof.cpp
    Hook/VMClassLoaderHook.cpp
    Hook/UnixFileSystemHook.cpp
    Hook/BinderHook.cpp
    Hook/BaseHook.cpp
    JniHook/JniHook.cpp)
target_include_directories(nexbox PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(nexbox PRIVATE nexbox_dobby xdl log android z)

# Keep ndk-build's optimization levels and symbolizable Debug objects.
foreach(target IN ITEMS nexbox xdl)
    target_compile_options(${target} PRIVATE
        -g -ffunction-sections -fdata-sections
        "$<$<CONFIG:Debug>:-O0;-UNDEBUG;-fno-limit-debug-info;-fno-omit-frame-pointer>"
        "$<$<NOT:$<CONFIG:Debug>>:-O2;-DNDEBUG>")
endforeach()
# Keep native diagnostics visible without making the existing warning baseline fatal.
target_compile_options(nexbox PRIVATE
    -Wno-error=format-security -fvisibility=hidden
    "$<$<COMPILE_LANGUAGE:CXX>:-fms-extensions;-fexceptions;-fno-rtti>")

# The core used ARM instructions; the C-only xDL kept ndk-build's Thumb default.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(nexbox PRIVATE -marm)
    target_link_options(nexbox PRIVATE -marm)
    target_compile_options(xdl PRIVATE -mthumb "$<$<NOT:$<CONFIG:Debug>>:-Oz>")
endif()

# AGP strips the packaged copy; local diagnostics need the matching Debug ELF.
target_link_options(nexbox PRIVATE
    # Dobby, xDL and the static C++ runtime are private to this library.
    -Wl,--exclude-libs,ALL
    -Wl,--gc-sections,-z,max-page-size=16384
    "$<$<NOT:$<CONFIG:Debug>>:-Wl,--strip-all>")
