cmake_minimum_required(VERSION 3.22.1)
project(pdfium_jni CXX)

set(PREBUILT ${CMAKE_CURRENT_SOURCE_DIR}/../../../prebuilt)

add_library(pdfium SHARED IMPORTED)
set_target_properties(pdfium PROPERTIES
    IMPORTED_LOCATION ${PREBUILT}/jniLibs/${ANDROID_ABI}/libpdfium.so)

add_library(pdfium_jni SHARED pdfium_jni.cpp)
target_include_directories(pdfium_jni PRIVATE ${PREBUILT}/include)
target_compile_options(pdfium_jni PRIVATE -Wall -Wextra -O2)
target_link_options(pdfium_jni PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(pdfium_jni pdfium jnigraphics log)
