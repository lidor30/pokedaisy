# pk_api.h's host test: the C API built off Android (as the iPad app builds it) over linux/make_diagnostic.py's ROMs.
#   cmake -S app/src/main/cpp/test -B build/pk_api_test && cmake --build build/pk_api_test && ctest --test-dir build/pk_api_test
cmake_minimum_required(VERSION 3.22.1)
project(pk_api_test C CXX)
include("${CMAKE_CURRENT_SOURCE_DIR}/../pk_api.cmake")

add_executable(pk_api_test pk_api_test.c)
target_link_libraries(pk_api_test PRIVATE pk_api)

enable_testing()
find_package(Python3 COMPONENTS Interpreter REQUIRED)
add_test(NAME diagnostic-roms COMMAND ${Python3_EXECUTABLE} "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../linux/make_diagnostic.py"
    WORKING_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}")
set_tests_properties(diagnostic-roms PROPERTIES FIXTURES_SETUP diagnostics)
add_test(NAME pk_api COMMAND pk_api_test "${CMAKE_CURRENT_BINARY_DIR}")
set_tests_properties(pk_api PROPERTIES FIXTURES_REQUIRED diagnostics TIMEOUT 60)
