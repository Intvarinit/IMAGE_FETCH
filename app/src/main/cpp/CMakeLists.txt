cmake_minimum_required(VERSION 3.22.1)
project(imagefetchraw CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# LibRaw：只编 CR3/CR2/NEF/ARW 等相机 RAW 的解码与显影，不编示例程序。
# 三个 *_ph.cpp 是「不带后处理」时的占位实现，会与正式实现重复定义，必须排除。
set(LIBRAW_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libraw)
file(GLOB_RECURSE LIBRAW_SOURCES CONFIGURE_DEPENDS
        "${LIBRAW_DIR}/src/*.cpp"
        "${LIBRAW_DIR}/src/*.c")
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX "_ph\\.cpp$")

add_library(libraw STATIC ${LIBRAW_SOURCES})
target_include_directories(libraw PUBLIC ${LIBRAW_DIR})
# NDK 不带 zlib 头文件，不启用 USE_ZLIB；CR3/CR2/NEF/ARW 解码不需要它。
target_compile_definitions(libraw PRIVATE LIBRAW_NOTHREADS)
target_compile_options(libraw PRIVATE -O2 -fexceptions -w)

add_library(imagefetchraw SHARED raw_developer.cpp)
target_include_directories(imagefetchraw PRIVATE
        ${LIBRAW_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/stb)
target_compile_options(imagefetchraw PRIVATE -O2 -fexceptions -Wall)
target_link_libraries(imagefetchraw libraw jnigraphics log)
