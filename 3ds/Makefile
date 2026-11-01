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

.PHONY: preview test-font 3ds decomps fixture-roms azahar-test azahar-battle-test tables clean

$(BUILD)/pd_font_test: core/pd_canvas.c core/pd_canvas.h core/pd_font.h core/pd_font_gen.c test/font_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ core/pd_canvas.c core/pd_font_gen.c test/font_test.c -lm

test-font: $(BUILD)/pd_font_test
	$(BUILD)/pd_font_test

$(BUILD)/pd_host_test: $(CORE_SRC) $(wildcard core/*.h) test/host_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(CORE_SRC) test/host_test.c -lm

test-host: $(BUILD)/pd_host_test
	$(BUILD)/pd_host_test

$(BUILD)/pd_stats_test: $(CORE_SRC) $(wildcard core/*.h) test/stats_test.c desktop/pd_png.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(CORE_SRC) test/stats_test.c desktop/pd_png.c -lm

test-stats: $(BUILD)/pd_stats_test
	python3 tools/gen_stats.py --check
	python3 tools/gen_experience.py --check
	@mkdir -p $(BUILD)/stats-shots
	$(BUILD)/pd_stats_test $(FIXTURES)/emerald_vanilla BPEE $(BUILD)/stats-shots
	$(BUILD)/pd_stats_test $(FIXTURES)/firered_vanilla BPRE $(BUILD)/stats-shots

$(BUILD)/pd_preview: $(CORE_SRC) $(wildcard core/*.h) desktop/pd_preview.c desktop/pd_png.c desktop/pd_png.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(CORE_SRC) desktop/pd_preview.c desktop/pd_png.c -lm

# The tabs that read the ROM (MAP, POKéDEX, GUIDE) need one: pret's decomps
# build byte-identical to retail (make decomps, below); without them those
# tabs show their "not available" notices. Point FR_ROM / EM_ROM at builds you
# already have (make preview FR_ROM=...).
FR_ROM ?= $(BUILD)/decomps/pokefirered/pokefirered_rev1.gba
EM_ROM ?= $(BUILD)/decomps/pokeemerald/pokeemerald.gba
rom_if = $(if $(wildcard $(1)),$(1),)

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

decomps:
	test/build_decomps.sh

tables:
	python3 tools/gen_experience.py
	python3 tools/gen_stats.py
	python3 tools/gen_tables.py
	python3 tools/gen_map_tables.py
	python3 tools/gen_guide.py
	python3 tools/gen_font.py
	python3 tools/gen_icon.py

# The repo is mounted whole: the build needs third_party/mgba next to 3ds/.
3ds:
	docker run --rm -v "$(REPO):/repo" -w /repo/3ds/ctr $(DEVKITARM_IMAGE) \
		bash -c "cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
			-DCMAKE_TOOLCHAIN_FILE=/repo/third_party/mgba/src/platform/3ds/CMakeToolchain.txt \
			&& cmake --build build -j$$(nproc) && cp build/pokedaisy.3dsx ."

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
	rm -rf $(BUILD) ctr/build ctr/pokedaisy.3dsx

# Optional host capability: the default 3DS settings remain available.
$(BUILD)/pd_host_display_test: $(CORE_SRC) $(wildcard core/*.h) test/host_display_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(CORE_SRC) test/host_display_test.c -lm

.PHONY: test-host-display
test-host-display: $(BUILD)/pd_host_display_test
	$(BUILD)/pd_host_display_test

# gamedata/ phase 2: the generated configs equal the hand-written ones they replaced.
$(BUILD)/pd_configs_gen_test: core/pd_configs_gen.h core/pd_game.h test/configs_gen_test.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ test/configs_gen_test.c

.PHONY: test-configs-gen
test-configs-gen: $(BUILD)/pd_configs_gen_test
	$(BUILD)/pd_configs_gen_test
