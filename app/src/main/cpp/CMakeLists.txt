cmake_minimum_required(VERSION 3.22.1)
project(norns_sc_bridge LANGUAGES CXX)

# Importa o núcleo upstream compilado pelo workflow Experimental scsynth Android.
set(SCSYNTH_LIBRARY "${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}/libscsynth.so")
if(NOT EXISTS "${SCSYNTH_LIBRARY}")
    message(FATAL_ERROR "libscsynth.so não encontrada para ABI ${ANDROID_ABI}: ${SCSYNTH_LIBRARY}")
endif()

add_library(scsynth SHARED IMPORTED)
set_target_properties(scsynth PROPERTIES IMPORTED_LOCATION "${SCSYNTH_LIBRARY}")

add_library(norns_sc_bridge SHARED sc_bridge.cpp norns_faust_fx_jni.cpp)
target_include_directories(norns_sc_bridge PRIVATE ${CMAKE_CURRENT_LIST_DIR})
target_compile_features(norns_sc_bridge PRIVATE cxx_std_17)
target_compile_options(norns_sc_bridge PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter)

find_library(log-lib log)
find_library(android-lib android)
find_library(dl-lib dl)

# A dependência explícita garante que libscsynth.so seja carregada junto com a ponte.
target_link_libraries(norns_sc_bridge PRIVATE scsynth ${log-lib} ${android-lib} ${dl-lib})
