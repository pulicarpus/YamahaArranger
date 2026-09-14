cmake_minimum_required(VERSION 3.22.1)
project(yamahaarranger)

# Oboe is consumed as a prefab package from the com.google.oboe:oboe AAR
# declared in app/build.gradle.kts. If you'd rather vendor Oboe's source
# directly (no AAR/prefab), clone https://github.com/google/oboe into
# app/src/main/cpp/oboe and replace the find_package block below with
# add_subdirectory(oboe) + the include dirs it exposes.
find_package(oboe REQUIRED CONFIG)

add_library(yamaha_arranger_native SHARED
        native_lib.cpp
        audio_engine.cpp
        voice.cpp
        style_parser.cpp
        smf_reader.cpp
)

target_include_directories(yamaha_arranger_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(yamaha_arranger_native
        oboe::oboe
        android
        log
)

target_compile_options(yamaha_arranger_native PRIVATE -Wall -Wextra)
