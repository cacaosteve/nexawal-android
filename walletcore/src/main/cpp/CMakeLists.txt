cmake_minimum_required(VERSION 3.22.1)

project(nexawal_walletcore_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build the JNI bridge as a shared library loaded by System.loadLibrary(...)
add_library(walletcore_jni SHARED
    walletcore_jni.cpp
)

# Make the local header visible
target_include_directories(walletcore_jni PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}
)

# Android log (optional, but commonly useful)
find_library(log-lib log)

# Link against Android log and the Rust wallet core shared library.
#
# IMPORTANT:
# We do NOT build libmonerowalletcore.so with CMake; it is shipped via jniLibs.
# The link step still needs to resolve symbols, so we declare it as an IMPORTED library.
add_library(monerowalletcore SHARED IMPORTED)

# Tell CMake where the prebuilt .so is located for the current ABI.
# ANDROID_ABI values are like: arm64-v8a, x86_64, armeabi-v7a, x86
set_target_properties(monerowalletcore PROPERTIES
    IMPORTED_NO_SONAME TRUE
    IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmonerowalletcore.so
)

target_link_libraries(walletcore_jni
    monerowalletcore
    ${log-lib}
)
