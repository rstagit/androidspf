cmake_minimum_required(VERSION 3.22.1)
project(androidspf_jni)

add_library(androidspf_jni SHARED jni_bridge.c)

find_library(log-lib log)

target_link_libraries(androidspf_jni ${log-lib} dl)
