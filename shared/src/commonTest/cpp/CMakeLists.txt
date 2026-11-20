# pk_api.h's host test: the C API built off Android (as the iPad app builds it) over linux/make_diagnostic.py's ROMs.
#   cmake -S shared/src/commonTest/cpp -B build/pk_api_test && cmake --build build/pk_api_test && ctest --test-dir build/pk_api_test
cmake_minimum_required(VERSION 3.22.1)
project(pk_api_test C CXX)
include("${CMAKE_CURRENT_SOURCE_DIR}/../../commonMain/cpp/pk_api.cmake")

add_executable(pk_api_test pk_api_test.c)
target_link_libraries(pk_api_test PRIVATE pk_api)

enable_testing()
find_package(Python3 COMPONENTS Interpreter REQUIRED)
add_test(NAME diagnostic-roms COMMAND ${Python3_EXECUTABLE} "${CMAKE_CURRENT_SOURCE_DIR}/../../../../linux/make_diagnostic.py"
    WORKING_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}")
set_tests_properties(diagnostic-roms PROPERTIES FIXTURES_SETUP diagnostics)
add_test(NAME pk_api COMMAND pk_api_test "${CMAKE_CURRENT_BINARY_DIR}")
set_tests_properties(pk_api PROPERTIES FIXTURES_REQUIRED diagnostics TIMEOUT 60)

add_executable(pk_state_test pk_state_test.c)
target_link_libraries(pk_state_test PRIVATE pk_api)
add_test(NAME pk_state COMMAND pk_state_test "${CMAKE_CURRENT_BINARY_DIR}")
set_tests_properties(pk_state PROPERTIES FIXTURES_REQUIRED diagnostics TIMEOUT 60)

add_executable(pk_rewind_api_test pk_rewind_api_test.c)
target_link_libraries(pk_rewind_api_test PRIVATE pk_api)
add_test(NAME pk_rewind_api COMMAND pk_rewind_api_test "${CMAKE_CURRENT_BINARY_DIR}")
set_tests_properties(pk_rewind_api PROPERTIES FIXTURES_REQUIRED diagnostics TIMEOUT 60)

add_executable(pk_cheats_api_test pk_cheats_api_test.c)
target_link_libraries(pk_cheats_api_test PRIVATE pk_api)
add_test(NAME pk_cheats_api COMMAND pk_cheats_api_test "${CMAKE_CURRENT_BINARY_DIR}")
set_tests_properties(pk_cheats_api PROPERTIES FIXTURES_REQUIRED diagnostics TIMEOUT 60)

add_executable(pk_calls_api_test pk_calls_api_test.c)
target_link_libraries(pk_calls_api_test PRIVATE pk_api)
add_test(NAME pk_calls_api COMMAND pk_calls_api_test "${CMAKE_CURRENT_BINARY_DIR}")
set_tests_properties(pk_calls_api PROPERTIES FIXTURES_REQUIRED diagnostics TIMEOUT 60)

add_executable(pk_flight_api_test pk_flight_api_test.c)
target_link_libraries(pk_flight_api_test PRIVATE pk_api)
add_test(NAME pk_flight_api COMMAND pk_flight_api_test "${CMAKE_CURRENT_BINARY_DIR}")
set_tests_properties(pk_flight_api PROPERTIES FIXTURES_REQUIRED diagnostics TIMEOUT 60)

add_executable(pk_follower_api_test pk_follower_api_test.c)
target_link_libraries(pk_follower_api_test PRIVATE pk_api)
add_test(NAME pk_follower_api COMMAND pk_follower_api_test "${CMAKE_CURRENT_BINARY_DIR}")
set_tests_properties(pk_follower_api PROPERTIES FIXTURES_REQUIRED diagnostics TIMEOUT 60)

add_executable(pk_wide_api_test pk_wide_api_test.c)
target_link_libraries(pk_wide_api_test PRIVATE pk_api)
add_test(NAME pk_wide_api COMMAND pk_wide_api_test "${CMAKE_CURRENT_BINARY_DIR}")
set_tests_properties(pk_wide_api PROPERTIES FIXTURES_REQUIRED diagnostics TIMEOUT 60)

add_executable(pk_link_api_test pk_link_api_test.c)
target_link_libraries(pk_link_api_test PRIVATE pk_api)
add_test(NAME pk_link_api COMMAND pk_link_api_test "${CMAKE_CURRENT_BINARY_DIR}")
set_tests_properties(pk_link_api PROPERTIES FIXTURES_REQUIRED diagnostics TIMEOUT 60)

include("${CMAKE_CURRENT_SOURCE_DIR}/../../commonMain/cpp/pk_ra.cmake")
add_executable(pk_ra_requests_test pk_ra_requests_test.c)
target_link_libraries(pk_ra_requests_test PRIVATE pk_ra_requests)
add_test(NAME pk_ra_requests COMMAND pk_ra_requests_test)
set_tests_properties(pk_ra_requests PROPERTIES TIMEOUT 30)

add_executable(pk_ra_client_test pk_ra_client_test.c)
target_link_libraries(pk_ra_client_test PRIVATE pk_ra_client)
add_test(NAME pk_ra_client COMMAND pk_ra_client_test "${CMAKE_CURRENT_BINARY_DIR}")
set_tests_properties(pk_ra_client PROPERTIES FIXTURES_REQUIRED diagnostics TIMEOUT 30)
