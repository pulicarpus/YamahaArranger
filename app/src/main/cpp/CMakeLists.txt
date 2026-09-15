cmake_minimum_required(VERSION 3.22.1)
project(yamahaarranger)

find_package(oboe REQUIRED CONFIG)

set(JNILIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI})

add_library(fluidsynth SHARED IMPORTED)
set_target_properties(fluidsynth PROPERTIES
    IMPORTED_LOCATION ${JNILIBS_DIR}/libfluidsynth.so
)

add_library(fluidsynth_assetloader SHARED IMPORTED)
set_target_properties(fluidsynth_assetloader PROPERTIES
    IMPORTED_LOCATION ${JNILIBS_DIR}/libfluidsynth-assetloader.so
)

add_library(yamaha_arranger_native SHARED
    native_lib.cpp
    audio_engine.cpp
    voice.cpp
    soundfont_player.cpp
    style_parser.cpp
    smf_reader.cpp
)

target_include_directories(yamaha_arranger_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/fluidsynth/include
)

target_link_libraries(yamaha_arranger_native
    oboe::oboe
    android
    log
    fluidsynth
    fluidsynth_assetloader
)

target_compile_options(yamaha_arranger_native PRIVATE -Wall -Wextra)