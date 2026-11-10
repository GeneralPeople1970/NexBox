# parallel-hashmap by Gregory Popovitch; see LICENSE.
# NexBox vendors the runtime headers; upstream tests and examples are not included.

cmake_minimum_required(VERSION 3.13)

list (APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")

include(DetectVersion)

if(NOT CMAKE_CXX_STANDARD)
    set(CMAKE_CXX_STANDARD 11)
endif()

if(NOT CMAKE_CXX_STANDARD_REQUIRED)
    set(CMAKE_CXX_STANDARD_REQUIRED ON)
endif()

if(NOT DEFINED PHMAP_MASTER_PROJECT)
    set(PHMAP_MASTER_PROJECT OFF)
    if(CMAKE_CURRENT_SOURCE_DIR STREQUAL CMAKE_SOURCE_DIR)
        set(PHMAP_MASTER_PROJECT ON)
    endif()
endif()

project(phmap VERSION ${DETECTED_PHMAP_VERSION} LANGUAGES CXX)

option(PHMAP_INSTALL "Enable installation" ${PHMAP_MASTER_PROJECT})


set(PHMAP_DIR parallel_hashmap)
set(PHMAP_HEADERS ${CMAKE_CURRENT_SOURCE_DIR}/${PHMAP_DIR}/phmap.h
                  ${CMAKE_CURRENT_SOURCE_DIR}/${PHMAP_DIR}/phmap_base.h
                  ${CMAKE_CURRENT_SOURCE_DIR}/${PHMAP_DIR}/phmap_bits.h
                  ${CMAKE_CURRENT_SOURCE_DIR}/${PHMAP_DIR}/phmap_config.h
                  ${CMAKE_CURRENT_SOURCE_DIR}/${PHMAP_DIR}/phmap_dump.h
                  ${CMAKE_CURRENT_SOURCE_DIR}/${PHMAP_DIR}/phmap_fwd_decl.h
                  ${CMAKE_CURRENT_SOURCE_DIR}/${PHMAP_DIR}/phmap_utils.h
                  ${CMAKE_CURRENT_SOURCE_DIR}/${PHMAP_DIR}/meminfo.h
                  ${CMAKE_CURRENT_SOURCE_DIR}/${PHMAP_DIR}/btree.h)

add_library(${PROJECT_NAME} INTERFACE)

target_sources(${PROJECT_NAME} INTERFACE ${PHMAP_HEADERS})

target_include_directories(
     ${PROJECT_NAME} INTERFACE
     $<BUILD_INTERFACE:${PROJECT_SOURCE_DIR}>
     $<INSTALL_INTERFACE:include>)

if(PHMAP_INSTALL)
    include(GNUInstallDirs)
    include(CMakePackageConfigHelpers)

    install(
        DIRECTORY ${PROJECT_SOURCE_DIR}/${PHMAP_DIR}/
        DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/${PHMAP_DIR})

    install(TARGETS ${PROJECT_NAME}
            EXPORT ${PROJECT_NAME}-targets)

    export(EXPORT ${PROJECT_NAME}-targets
           FILE "${CMAKE_CURRENT_BINARY_DIR}/${PROJECT_NAME}Targets.cmake")
endif()

if(MSVC)
    add_compile_options("$<$<COMPILE_LANGUAGE:CXX>:/bigobj>")
endif()
