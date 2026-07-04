# whisper.cpp JNI bridge (§3.4). Enabled with -PwhisperNative; requires the
# third_party/whisper.cpp submodule.
cmake_minimum_required(VERSION 3.22)
project(whisper_percept CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(WHISPER_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/whisper.cpp" ABSOLUTE)

if(NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "whisper.cpp submodule missing at ${WHISPER_DIR}; run: "
        "git submodule update --init third_party/whisper.cpp")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory("${WHISPER_DIR}" whisper_build EXCLUDE_FROM_ALL)

add_library(whisper_percept SHARED whisper_percept.cpp)

target_include_directories(whisper_percept PRIVATE
    "${WHISPER_DIR}/include"
    "${WHISPER_DIR}/ggml/include")

find_library(ANDROID_LOG_LIB log)

target_link_libraries(whisper_percept PRIVATE whisper ${ANDROID_LOG_LIB})

# Risk 5: 16 KB page-size forward compatibility for Android 15+.
target_link_options(whisper_percept PRIVATE "-Wl,-z,max-page-size=16384")
