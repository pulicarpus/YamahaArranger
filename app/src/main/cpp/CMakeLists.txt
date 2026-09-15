cmake_minimum_required(VERSION 3.22.1)
project(yamahaarranger)

find_package(oboe REQUIRED CONFIG)

add_library(yamaha_arranger_native SHARED
    native_lib.cpp
    audio_engine.cpp
    voice.cpp
    soundfont_player.cpp
    tsf_impl.cpp
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