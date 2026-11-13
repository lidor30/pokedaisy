# PokeDaisy for the Nintendo 3DS (homebrew) - see README.md.
#
#   make preview   render the companion from the RAM fixtures into build/shots/
#   make 3ds       build ctr/pokedaisy.3dsx (devkitARM, in Docker)
#   make decomps   build pret's FireRed / Emerald (= retail) for the tabs that read a ROM
#   make fixture-roms  build/fixture-*.gba: test ROMs holding a RAM fixture
#   make azahar-test   run the .3dsx in the Azahar emulator, screenshots in build/azahar/
#   make tables    regenerate core/*_gen.c (the app's Kotlin tables + font) and ctr/icon.png

REPO := $(abspath ..)
FIXTURES := $(REPO)/app/src/test/resources/fixtures
BUILD := build
CC ?= cc
CFLAGS ?= -std=c11 -O2 -Wall -Wextra -Werror
CORE_SRC := $(wildcard core/*.c)
DEVKITARM_IMAGE := devkitpro/devkitarm:latest

.PHONY: preview library-preview docs-shots test-save 3ds-dev test-shiny test-roms test-gbguide test-gbdex test-dex test-font 3ds decomps fixture-roms azahar-test azahar-battle-test tables clean

$(BUILD)/pd_font_test: core/pd_canvas.c core/pd_canvas.h core/pd_font.h core/pd_font_gen.c test/font_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ core/pd_canvas.c core/pd_font_gen.c test/font_test.c -lm

test-font: $(BUILD)/pd_font_test
	$(BUILD)/pd_font_test

$(BUILD)/pd_stats_test: $(CORE_SRC) $(wildcard core/*.h) test/stats_test.c desktop/pd_png.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(CORE_SRC) test/stats_test.c desktop/pd_png.c -lm

$(BUILD)/pd_dex_test: $(CORE_SRC) $(wildcard core/*.h) test/dex_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(filter-out core/pd_game.c,$(CORE_SRC)) test/dex_test.c -lm

$(BUILD)/pd_gb_dex_test: $(CORE_SRC) $(wildcard core/*.h) test/gb_dex_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(filter-out core/pd_game.c,$(CORE_SRC)) test/gb_dex_test.c -lm

# make test-gbdex: the Game Boy POKéDEX flags vs the app's captured WRAM (fixtures dir = $(FIXTURES)).
test-gbdex: $(BUILD)/pd_gb_dex_test
	$(BUILD)/pd_gb_dex_test $(FIXTURES) $(ROMS)

$(BUILD)/pd_gb_guide_test: $(CORE_SRC) $(wildcard core/*.h) test/gb_guide_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(filter-out core/pd_game.c,$(CORE_SRC)) test/gb_guide_test.c -lm

# make test-gbguide: Red / Blue / Yellow's NEXT BOSS vs the app's facts and fixtures.
test-gbguide: $(BUILD)/pd_gb_guide_test
	$(BUILD)/pd_gb_guide_test $(FIXTURES)

$(BUILD)/pd_rom_ids_test: $(CORE_SRC) $(wildcard core/*.h) test/rom_ids_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(CORE_SRC) test/rom_ids_test.c -lm

# make test-roms ROMS=<dir>: every ROM in the folders under <dir> is recognised.
test-roms: $(BUILD)/pd_rom_ids_test
	$(BUILD)/pd_rom_ids_test $(ROMS)

$(BUILD)/pd_shiny_test: $(CORE_SRC) $(wildcard core/*.h) test/shiny_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(CORE_SRC) test/shiny_test.c -lm

# make test-shiny ROMS=<dir with the ROMs>: retail Emerald's shiny marks and icons on the emerald_shiny_battle fixture.
test-shiny: $(BUILD)/pd_shiny_test
	$(BUILD)/pd_shiny_test $(FIXTURES) $(ROMS)

$(BUILD)/pd_card_test: $(CORE_SRC) $(wildcard core/*.h) test/card_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(filter-out core/pd_game.c,$(CORE_SRC)) test/card_test.c -lm

$(BUILD)/pd_guide_test: $(CORE_SRC) $(wildcard core/*.h) test/guide_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(filter-out core/pd_game.c,$(CORE_SRC)) test/guide_test.c -lm

# make test-guide ROMS=<dir with the ROMs>: every hack boss team reads sanely from its ROM.
test-guide: $(BUILD)/pd_guide_test
	$(BUILD)/pd_guide_test $(ROMS) $(FIXTURES)

# make test-card ROMS=<dir with the ROMs>: the cards vs the CRCs of TrainerCardTest.
test-card: $(BUILD)/pd_card_test
	$(BUILD)/pd_card_test $(FIXTURES) $(ROMS)

test-dex: $(BUILD)/pd_dex_test
	$(BUILD)/pd_dex_test $(FIXTURES)

test-stats: $(BUILD)/pd_stats_test
	python3 tools/gen_stats.py --check
	python3 tools/gen_experience.py --check
	@mkdir -p $(BUILD)/stats-shots
	$(BUILD)/pd_stats_test $(FIXTURES)/emerald_vanilla BPEE $(BUILD)/stats-shots
	$(BUILD)/pd_stats_test $(FIXTURES)/firered_vanilla BPRE $(BUILD)/stats-shots

$(BUILD)/pd_preview: $(CORE_SRC) $(wildcard core/*.h) desktop/pd_preview.c desktop/pd_png.c desktop/pd_png.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(CORE_SRC) desktop/pd_preview.c desktop/pd_png.c -lm

# The library on real files (both screens a PNG per page): ROMs and saves are read where they are, never written - the
# save tools' writes land in build/library-shots/. LIBRARY_ROMS / LIBRARY_SAVES move them.
LIBRARY_ROMS ?= $(HOME)/Downloads/Game ROMs & Emulation/gba
LIBRARY_SAVES ?= $(HOME)/Documents/RetroArch/saves/mGBA
$(BUILD)/pd_library_preview: $(CORE_SRC) $(wildcard core/*.h) desktop/pd_library_preview.c desktop/pd_png.c desktop/pd_png.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(CORE_SRC) desktop/pd_library_preview.c desktop/pd_png.c -lm

library-preview: $(BUILD)/pd_library_preview
	@mkdir -p $(BUILD)/library-shots
	$(BUILD)/pd_library_preview "$(LIBRARY_ROMS)" "$(LIBRARY_SAVES)" $(BUILD)/library-shots $(FIXTURES)/firered_vanilla "$(LIBRARY_ROMS)/Pokemon - FireRed Version (USA, Europe) (Rev 1).gba"

# The install guide's screenshots (docs/manual/3ds.md): the library on made-up files, no ROM or save read.
DOCS_SHOTS ?= ../docs/manual/shots
$(BUILD)/pd_docs_shots: $(CORE_SRC) $(wildcard core/*.h) desktop/pd_docs_shots.c desktop/pd_png.c desktop/pd_png.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(CORE_SRC) desktop/pd_docs_shots.c desktop/pd_png.c -lm

docs-shots: $(BUILD)/pd_docs_shots
	$(BUILD)/pd_docs_shots $(DOCS_SHOTS)

# The save tools' core (pd_save.c) on real saves: read, written the game's way and read back, a move, the money, trades.
$(BUILD)/pd_save_test: $(CORE_SRC) $(wildcard core/*.h) test/save_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(CORE_SRC) test/save_test.c -lm

test-save: $(BUILD)/pd_save_test
	python3 tools/gen_save_tables.py --check
	$(BUILD)/pd_save_test "BPRE=$(LIBRARY_SAVES)/Pokemon - FireRed Version (USA, Europe) (Rev 1).srm" \
		"BPEE=$(LIBRARY_SAVES)/Pokemon - Emerald Version (USA, Europe).srm" "AXVE=$(LIBRARY_SAVES)/Pokemon - Ruby Version (USA, Europe) (Rev 1).sav"

# The tabs that read the ROM (MAP, POKéDEX, GUIDE) need one: pret's decomps
# build byte-identical to retail (make decomps, below); without them those
# tabs show their "not available" notices. Point FR_ROM / EM_ROM at builds you
# already have (make preview FR_ROM=...).
FR_ROM ?= $(BUILD)/decomps/pokefirered/pokefirered_rev1.gba
EM_ROM ?= $(BUILD)/decomps/pokeemerald/pokeemerald.gba
rom_if = $(if $(wildcard $(1)),$(1),)
# The Game Boy fixtures need their cart (the shots are skipped without it): retail Red, Blue, Yellow, Crystal, Gold,
# Silver, linked or copied under these names (make preview GB_ROMS=<dir>).
GB_ROMS ?= $(BUILD)/roms
gb_preview = $(if $(wildcard $(GB_ROMS)/$(2)),$(BUILD)/pd_preview $(FIXTURES)/$(1) GB $(BUILD)/shots $(1) $(GB_ROMS)/$(2),@echo "preview: no $(GB_ROMS)/$(2), $(1) skipped")

preview: $(BUILD)/pd_preview
	@mkdir -p $(BUILD)/shots
	$(BUILD)/pd_preview $(FIXTURES)/firered_vanilla BPRE $(BUILD)/shots firered $(call rom_if,$(FR_ROM))
	$(BUILD)/pd_preview $(FIXTURES)/emerald_vanilla BPEE $(BUILD)/shots emerald $(call rom_if,$(EM_ROM))
	# German Emerald keeps English's RAM layout: the only Emerald battle fixture
	# (wild; the preview also turns it into a trainer battle, kept as a fixture
	# of its own in build/fixtures/emerald_trainer for make fixture-roms).
	@mkdir -p $(BUILD)/fixtures/emerald_trainer
	PD_PREVIEW_TRAINER_DIR=$(BUILD)/fixtures/emerald_trainer \
		$(BUILD)/pd_preview $(FIXTURES)/emerald_de_battle BPEE $(BUILD)/shots emerald-battle $(call rom_if,$(EM_ROM))
	$(call gb_preview,red,red.gb)
	$(call gb_preview,blue,blue.gb)
	$(call gb_preview,yellow,yellow.gb)
	$(call gb_preview,crystal,crystal.gbc)
	$(call gb_preview,gold,gold.gbc)
	$(call gb_preview,silver,silver.gbc)

decomps:
	test/build_decomps.sh

tables:
	python3 tools/gen_experience.py
	python3 tools/gen_stats.py
	python3 tools/gen_tables.py
	python3 tools/gen_map_tables.py
	python3 tools/gen_guide.py
	python3 tools/gen_gb_world.py
	python3 tools/gen_randomized.py
	python3 tools/gen_font.py
	python3 tools/gen_icon.py
	python3 tools/gen_icons.py
	python3 tools/gen_save_tables.py

# The repo is mounted whole: the build needs third_party/mgba next to 3ds/.
3ds:
	docker run --rm -v "$(REPO):/repo" -w /repo/3ds/ctr $(DEVKITARM_IMAGE) \
		bash -c "cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
			-DCMAKE_TOOLCHAIN_FILE=/repo/third_party/mgba/src/platform/3ds/CMakeToolchain.txt \
			&& cmake --build build -j$$(nproc) && cp build/pokedaisy.3dsx ."

# The dev build: the Wi-Fi link (tools/pdlink.py) and an SD folder of its own. Never a player's build.
3ds-dev:
	docker run --rm -v "$(REPO):/repo" -w /repo/3ds/ctr $(DEVKITARM_IMAGE) \
		bash -c "cmake -S . -B build-dev -DCMAKE_BUILD_TYPE=Release -DPD_DEV=ON \
			-DCMAKE_TOOLCHAIN_FILE=/repo/third_party/mgba/src/platform/3ds/CMakeToolchain.txt \
			&& cmake --build build-dev -j$$(nproc) && cp build-dev/pokedaisy.3dsx pokedaisy-dev.3dsx"

# pd_sim: the real cores (armGBA's interpreter for GBA games, mGBA for Game Boy ones), the real ROMs and the app's host
# logic, no console (desktop/sim/).
$(BUILD)/sim/pd_sim: $(CORE_SRC) $(wildcard core/*.h) $(wildcard host/*) $(wildcard emu/*) desktop/sim/pd_sim.c desktop/sim/CMakeLists.txt \
		$(wildcard ../linux/*.c ../linux/*.h ../app/src/main/cpp/pk_*.c ../app/src/main/cpp/pk_*.h)
	cmake -S desktop/sim -B $(BUILD)/sim -DCMAKE_BUILD_TYPE=RelWithDebInfo -DCMAKE_POLICY_VERSION_MINIMUM=3.5 >/dev/null
	cmake --build $(BUILD)/sim --target pd_sim -j8

.PHONY: sim sim-test
sim: $(BUILD)/sim/pd_sim

# Each script in test/sim_*.txt, after sim_boot.txt, on the player's ROMs and saves (the saves are copied first).
ROM_DIR ?= $(HOME)/Downloads/Game ROMs & Emulation/gba
SAVE_DIR ?= $(HOME)/Documents/RetroArch/saves/mGBA
FR_NAME := Pokemon - FireRed Version (USA, Europe) (Rev 1)
EM_NAME := Pokemon - Emerald Version (USA, Europe)
sim-test: sim
	@mkdir -p $(BUILD)/sim/out
	@for g in "$(FR_NAME)" "$(EM_NAME)"; do for t in test/sim_[!bg]*.txt test/sim_battle.txt; do \
		[ "$$t" = test/sim_boot.txt ] && continue; \
		o="$(BUILD)/sim/out/$$(echo "$$g" | tr -cd 'A-Za-z')-$$(basename $$t .txt)"; mkdir -p "$$o"; \
		cat test/sim_boot.txt "$$t" | $(BUILD)/sim/pd_sim "$(ROM_DIR)/$$g.gba" "$(SAVE_DIR)/$$g.srm" "$$o" | tail -3 | sed "s|^|$$t: |" || exit 1; \
	done; done

# The Game Boy games' script on English Yellow (its ROM in GB_ROM_DIR, its save copied first).
GB_ROM_DIR ?= $(HOME)/Downloads/Game ROMs & Emulation/gbc
YELLOW_NAME := Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced)
# Every Game Boy cart's dex pages, pictures and wild lists against the app's own reading: EXPORT is a gameExport's folder.
.PHONY: gb-check
gb-check:
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -Icore -o $(BUILD)/gb_check $(CORE_SRC) test/gb_check.c -lm
	python3 test/gb_check.py $(BUILD)/gb_check "$(EXPORT)" "$(HOME)/Downloads/Game ROMs & Emulation"

.PHONY: sim-test-gb
sim-test-gb: sim
	@mkdir -p $(BUILD)/sim/out/yellow
	@$(BUILD)/sim/pd_sim "$(GB_ROM_DIR)/$(YELLOW_NAME).gb" "$(SAVE_DIR)/$(YELLOW_NAME).sav" $(BUILD)/sim/out/yellow test/sim_gb_yellow.txt | tail -3

# Test ROMs that load a RAM fixture (test/fixture_rom.c): the 3DS app run on
# them, in Azahar or on a console, shows the fixtures' party with no Pokémon ROM.
# With the decomps built, also build/fixture-*-rom.gba: the same over the
# decomp's ROM, so MAP / POKéDEX / GUIDE have the game's tables (build/ only).
fixture-roms:
	docker run --rm -v "$(REPO):/repo" $(DEVKITARM_IMAGE) bash /repo/3ds/test/build_fixture_roms.sh

# The real .3dsx in the Azahar emulator (linuxserver/azahar image, Xvfb inside)
# on the FireRed fixture ROM (over the decomp's when built), tapped through
# like a player would: the game list, PARTY (SHARP 1.5x, the default), BAG,
# SETTINGS, the SCREEN pick-list, STRETCH, PIXEL, fast-forward on, a saved
# state and loading it, MAP (a tapped place, PLACES), POKéDEX (an entry, the
# next), GUIDE (its notice, HERE, BOSS, a hint), the TRAINER CARD (from
# SETTINGS' title window, x 265 y 40) and its back - screenshots in
# build/azahar/*.png (needs ImageMagick's convert). Taps are window
# coordinates: the bottom screen's (x, y) is (480 + x, 478 + y); the tab
# chips' centres are at y 13, x 27 / 78 / 129 / 180 / 231, FF 273, gear 303
# (`PD_PREVIEW_HITS=1 build/pd_preview ...` prints every shot's targets).
AZAHAR_IMAGE := linuxserver/azahar:latest
AZAHAR_ROM = $(if $(wildcard $(BUILD)/fixture-firered_vanilla-rom.gba),fixture-firered_vanilla-rom.gba,fixture-firered_vanilla.gba)
AZAHAR_SHOTS := 01-menu 02-party 03-bag 04-settings 05-pick 06-stretch 07-pixel 08-ff 09-saved 10-loaded \
	11-map 12-map-tapped 13-places 14-dex 15-dex-entry 16-dex-next 17-guide-notice 18-guide-here \
	19-guide-boss 20-guide-hint 21-card 22-card-back
azahar-test: 3ds fixture-roms
	rm -rf $(BUILD)/azahar && mkdir -p $(BUILD)/azahar
	docker run --rm -v "$(REPO):/repo" -v "$(abspath $(BUILD))/azahar:/out" --entrypoint /bin/sh $(AZAHAR_IMAGE) \
		/repo/3ds/test/azahar_run.sh $(AZAHAR_ROM) \
		shot:01-menu a wait:8 shot:02-party \
		click:558,491 wait:1 shot:03-bag \
		click:783,491 wait:1 shot:04-settings \
		click:580,626 wait:1 shot:05-pick click:580,596 wait:2 shot:06-stretch \
		click:580,626 wait:1 click:580,560 wait:2 shot:07-pixel \
		click:753,491 wait:3 shot:08-ff click:753,491 \
		click:580,662 wait:1 click:586,628 wait:2 shot:09-saved \
		click:580,680 wait:1 click:586,628 wait:2 shot:10-loaded \
		click:609,491 wait:1 shot:11-map click:600,578 wait:1 shot:12-map-tapped \
		click:767,546 wait:1 shot:13-places x \
		click:711,491 wait:1 shot:14-dex click:640,641 wait:1 shot:15-dex-entry \
		click:701,696 wait:1 shot:16-dex-next \
		click:660,491 wait:1 shot:17-guide-notice click:726,704 wait:1 shot:18-guide-here \
		click:576,517 wait:1 shot:19-guide-boss \
		click:765,517 wait:1 click:640,567 wait:1 shot:20-guide-hint \
		click:783,491 wait:1 click:745,518 wait:2 shot:21-card \
		click:640,599 wait:2 shot:22-card-back
	for s in $(AZAHAR_SHOTS); do convert xwd:$(BUILD)/azahar/$$s.xwd -crop 640x500+320+230 $(BUILD)/azahar/$$s.png; done

# BATTLE in Azahar, on the trainer battle `make preview` builds from the wild
# one (build/fixtures/emerald_trainer, over the Emerald decomp's ROM): INFO
# with the FOE TEAM, SUGGESTIONS, an unseen foe tapped, the last one and its
# SUGGESTIONS - screenshots in build/azahar-battle/. The bar's buttons sit at
# y 219: SUGGESTIONS / INFO x 51, the foe slots x 188 / 225 / 262 / 299.
AZAHAR_BATTLE_SHOTS := 01-info 02-suggest 03-foe-unseen 04-foe-last 05-foe-last-suggest
azahar-battle-test: 3ds fixture-roms
	rm -rf $(BUILD)/azahar-battle && mkdir -p $(BUILD)/azahar-battle
	docker run --rm -v "$(REPO):/repo" -v "$(abspath $(BUILD))/azahar-battle:/out" --entrypoint /bin/sh $(AZAHAR_IMAGE) \
		/repo/3ds/test/azahar_run.sh fixture-emerald_trainer-rom.gba \
		a wait:8 shot:01-info \
		click:531,697 wait:1 shot:02-suggest click:531,697 wait:1 \
		click:705,697 wait:1 shot:03-foe-unseen \
		click:779,697 wait:1 shot:04-foe-last \
		click:531,697 wait:1 shot:05-foe-last-suggest
	for s in $(AZAHAR_BATTLE_SHOTS); do convert xwd:$(BUILD)/azahar-battle/$$s.xwd -crop 640x500+320+230 $(BUILD)/azahar-battle/$$s.png; done

clean:
	rm -rf $(BUILD) ctr/build ctr/build-dev ctr/pokedaisy.3dsx ctr/pokedaisy-dev.3dsx

# Optional host capability: the default 3DS settings remain available.
$(BUILD)/pd_host_display_test: $(CORE_SRC) $(wildcard core/*.h) test/host_display_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(CORE_SRC) test/host_display_test.c -lm

# gamedata/ phase 2: the generated configs equal the hand-written ones they replaced.
$(BUILD)/pd_configs_gen_test: $(CORE_SRC) $(wildcard core/*.h) test/configs_gen_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(CORE_SRC) test/configs_gen_test.c -lm

.PHONY: test-configs-gen
test-configs-gen: $(BUILD)/pd_configs_gen_test
	$(BUILD)/pd_configs_gen_test

.PHONY: test-host-display
test-host-display: $(BUILD)/pd_host_display_test
	$(BUILD)/pd_host_display_test
