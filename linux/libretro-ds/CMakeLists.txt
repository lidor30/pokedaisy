cmake_minimum_required(VERSION 3.18)
project(pokedaisy_ds_libretro LANGUAGES C CXX)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../..)
set(LR ${ROOT}/third_party/dsperate-lr)
set(DS ${LR}/DSperate)
if(NOT EXISTS ${DS}/src/core/nds.h)
  message(FATAL_ERROR "Run git submodule update --init --recursive third_party/dsperate-lr")
endif()
foreach(feature DSPERATE_SDL DSPERATE_HEADLESS DSPERATE_TESTS DSPERATE_CHEEVOS DSPERATE_NET DSPERATE_VULKAN)
  set(${feature} OFF CACHE BOOL "" FORCE)
endforeach()
add_subdirectory(${DS} dsperate EXCLUDE_FROM_ALL)
# The TrimUI SDK has no lto1; this path deliberately uses no release PGO.
set_property(TARGET dsperate_core PROPERTY INTERPROCEDURAL_OPTIMIZATION FALSE)
set(APP ${ROOT}/app/src/main/cpp/nds)
set(KOTLIN ${ROOT}/app/src/main/kotlin/com/pokedaisy/app)
find_package(Python3 COMPONENTS Interpreter REQUIRED)
set(DS_DATA ${CMAKE_CURRENT_BINARY_DIR}/data_gen.h)
set(DATA_READER ${ROOT}/linux/dsperate/gen_data.py)
set(DATA_INPUTS ${KOTLIN}/GameTitles.kt)
foreach(name Gen4Reader Gen4TablesPlatinum Gen4TablesHeartGold Gen4Dex Gen4TownMapPlatinum Gen4TownMapHeartGold PokegearMap Gen5TownMapBlack Gen5TownMapBlack2 Gen5Reader Gen5TablesBlack Gen5ItemsBlack Gen5TablesBlack2 NdsArt RegionMapModel MonStats Gen4Charmap)
  list(APPEND DATA_INPUTS ${KOTLIN}/companion/data/${name}.kt)
endforeach()
add_custom_command(OUTPUT ${DS_DATA}
  COMMAND ${Python3_EXECUTABLE} ${DATA_READER} --output ${DS_DATA}
  DEPENDS ${DATA_READER} ${DATA_INPUTS}
  VERBATIM)
add_custom_target(ds-app-data DEPENDS ${DS_DATA})
file(GLOB PD_CORE CONFIGURE_DEPENDS ${ROOT}/3ds/core/*.c)
add_library(pd_ds_presentation STATIC companion_ui.cpp layout.cpp battle_strip.cpp battle_style.cpp ${PD_CORE}
  ${ROOT}/linux/hud.c
  ${ROOT}/linux/quick_menu.c
  ${ROOT}/linux/dsperate/companion.cpp
  ${ROOT}/linux/dsperate/party_view.cpp
  ${ROOT}/linux/dsperate/bag_view.cpp
  ${ROOT}/linux/dsperate/dex_view.cpp
  ${ROOT}/linux/dsperate/map_art.cpp
  ${ROOT}/linux/dsperate/map_view.cpp
  ${APP}/pk_nds_field.cpp)
add_dependencies(pd_ds_presentation ds-app-data)
target_include_directories(pd_ds_presentation PUBLIC ${CMAKE_CURRENT_BINARY_DIR} ${LR}/include
  ${ROOT}/3ds/core ${ROOT}/linux/dsperate ${APP})
target_link_libraries(pd_ds_presentation PUBLIC m)

add_library(pokedaisy_ds_libretro SHARED pd_ds_libretro.cpp)
set_target_properties(pokedaisy_ds_libretro PROPERTIES PREFIX "")
target_include_directories(pokedaisy_ds_libretro PRIVATE ${LR} ${DS}/src)
target_link_libraries(pokedaisy_ds_libretro PRIVATE pd_ds_presentation dsperate_core pthread dl)
if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU" AND CMAKE_CXX_COMPILER_VERSION VERSION_LESS 9)
  target_link_libraries(pokedaisy_ds_libretro PRIVATE stdc++fs)
endif()
target_link_options(pokedaisy_ds_libretro PRIVATE -Wl,--version-script=${LR}/src/link.T -Wl,--no-undefined)

# A self-contained pak should not depend on firmware C++ runtime versions.
if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
  target_link_options(pokedaisy_ds_libretro PRIVATE -static-libstdc++ -static-libgcc)
endif()

include(CTest)
if(BUILD_TESTING)
  add_executable(ds-layout-test test/layout.cpp layout.cpp ${ROOT}/3ds/core/pd_canvas.c ${ROOT}/3ds/core/pd_font_gen.c ${ROOT}/3ds/core/pd_style.c)
  target_include_directories(ds-layout-test PRIVATE . ${APP} ${ROOT}/3ds/core)
  target_compile_options(ds-layout-test PRIVATE -UNDEBUG)
  target_link_libraries(ds-layout-test PRIVATE m)
  add_test(NAME ds-layout-policy COMMAND ds-layout-test)
  add_executable(ds-libretro-host test/host.c)
  target_include_directories(ds-libretro-host PRIVATE ${LR}/include)
  target_link_libraries(ds-libretro-host PRIVATE dl)
  target_compile_options(ds-libretro-host PRIVATE -UNDEBUG)
  # Run this target with either the stock wrapper or the enhanced core.
  set(DIAGNOSTIC ${CMAKE_CURRENT_BINARY_DIR}/diagnostic.nds)
  add_test(NAME ds-diagnostic-generate COMMAND ${Python3_EXECUTABLE} ${ROOT}/linux/dsperate/make_diagnostic.py ${DIAGNOSTIC})
  set_tests_properties(ds-diagnostic-generate PROPERTIES FIXTURES_SETUP diagnostic)
  file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/validation)
  add_test(NAME ds-libretro-contract COMMAND ds-libretro-host $<TARGET_FILE:pokedaisy_ds_libretro> ${DIAGNOSTIC} ${CMAKE_CURRENT_BINARY_DIR}/validation)
  set_tests_properties(ds-libretro-contract PROPERTIES FIXTURES_REQUIRED diagnostic TIMEOUT 90)
  add_executable(ds-companion-fixture test/fixture.cpp)
  add_executable(ds-shared-default-ui ${ROOT}/3ds/test/host_display_test.c)
  add_executable(ds-battle-strip-test test/battle_strip.cpp)
  target_include_directories(ds-battle-strip-test PRIVATE .)
  target_compile_options(ds-battle-strip-test PRIVATE -UNDEBUG)
  target_link_libraries(ds-battle-strip-test PRIVATE pd_ds_presentation)
  add_test(NAME ds-platinum-battle-strip COMMAND ds-battle-strip-test
    ${ROOT}/app/src/test/resources/fixtures/platinum_battle/mainram.bin
    ${ROOT}/app/src/test/resources/fixtures/black_wild/mainram.bin)
  add_executable(ds-settings-test test/settings.cpp)
  add_executable(ds-companion-parity test/companion_parity.cpp)
  foreach(test ds-companion-fixture ds-shared-default-ui ds-settings-test ds-companion-parity)
    target_compile_options(${test} PRIVATE -UNDEBUG)
    target_link_libraries(${test} PRIVATE pd_ds_presentation)
  endforeach()
  target_include_directories(ds-settings-test PRIVATE .)
  target_include_directories(ds-companion-parity PRIVATE .)
  add_test(NAME ds-shared-default-ui COMMAND ds-shared-default-ui)
  if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU" AND CMAKE_CXX_COMPILER_VERSION VERSION_LESS 9)
    target_link_libraries(ds-settings-test PRIVATE stdc++fs)
  endif()
  add_test(NAME ds-settings COMMAND ds-settings-test ${ROOT}/app/src/test/resources/fixtures/platinum/mainram.bin ${CMAKE_CURRENT_BINARY_DIR}/validation)
  add_test(NAME ds-companion-fixture COMMAND ds-companion-fixture ${ROOT}/app/src/test/resources/fixtures/platinum/mainram.bin)
  set(DS_FIXTURES ${ROOT}/app/src/test/resources/fixtures)
  add_test(NAME ds-companion-parity COMMAND ds-companion-parity
    ${DS_FIXTURES}/heartgold/mainram.bin ${DS_FIXTURES}/soulsilver/mainram.bin ${DS_FIXTURES}/black/mainram.bin)
endif()
