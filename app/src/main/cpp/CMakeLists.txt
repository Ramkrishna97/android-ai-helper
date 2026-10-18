cmake_minimum_required(VERSION 3.22.1)

project("llm_bridge")

add_library(
    llm_bridge
    SHARED
    llm_bridge.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    llm_bridge
    ${log-lib}
)
