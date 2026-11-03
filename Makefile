# Convenience wrappers around PokeDaisy's workflows (`make help`). The real
# logic lives in Gradle, scripts/ and ui-preview/.
#
# Machine-local paths (decomp builds, mon icons) go in an untracked local.mk -
# copy local.mk.example. Variables can also be passed per run:
#   make preview MON_ICONS=/path/to/icons

SHELL := /bin/bash
-include local.mk

PKG           := com.pokedaisy.app
CAPTURE_IMAGE := pokedaisy-capture

# Read by scripts/decomps.py and ui-preview (see docs/DEVELOPMENT.md "Build").
export DECOMPS
export MON_ICONS

.DEFAULT_GOAL := help

.PHONY: help docs-shots docs-check submodules apk install run test clean shots preview menu-shots capture-image \
        check gamedata stress test-roms version bump release-apk symbols release release-local

# versionName / versionCode, read from app/build.gradle.kts.
VERSION      := $(shell sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)
VERSION_CODE := $(shell sed -n 's/^ *versionCode = \([0-9]*\)/\1/p' app/build.gradle.kts)
RELEASE_APK  := dist/PokeDaisy-$(VERSION).apk

help: ## Show this help
	@awk 'BEGIN{FS=":.*## "} \
	     /^##@ / {printf "\n\033[1m%s\033[0m\n", substr($$0,5); next} \
	     /^[a-zA-Z0-9_-]+:.*## / {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

##@ App

submodules: ## Fetch the mGBA, rcheevos and melonDS submodules (first build only)
	git submodule update --init third_party/mgba third_party/rcheevos third_party/melonds

apk: ## Build the debug APK
	./gradlew :app:assembleDebug
	@echo ">> app/build/outputs/apk/debug/app-debug.apk"

install: ## Build + install on a connected Android device (adb)
	./gradlew :app:installDebug

run: install ## Build + install + launch the library screen on the connected device
	adb shell am start -n $(PKG)/.LibraryActivity

gamedata: ## Regenerate the per-game code from gamedata/ (NativeConfigsGen.kt; see gamedata/README.md)
	python3 scripts/gen_gamedata.py

test: ## JVM unit tests (fixture decode tests, UI logic)
	./gradlew :app:testDebugUnitTest

clean: ## gradle clean
	./gradlew clean

##@ Checks (what CI runs)

check: ## Everything a PR must pass: unit tests, translations, game data, ui-preview stress + docs search, docs, debug build
	python3 scripts/check_translations.py
	python3 scripts/gen_gamedata.py --check
	./gradlew :app:testDebugUnitTest :app:assembleDebug
	./gradlew -p ui-preview stress
	./gradlew -p ui-preview docsSearchCheck
	./gradlew -p ui-preview keyedStateCheck
	cd website && npm ci && node --test test/docs.test.js test/docsearch.test.js && npm run build

docs-shots: ## Re-render the manual's screenshots (docs/manual/shots) with the invented save
	cd website && npm run docs-art && npm run build
	./gradlew -p ui-preview docsShots

docs-check: ## The manual parses, links resolve, no dashes, every setting documented, search cases pass
	cd website && node --test test/docs.test.js test/docsearch.test.js && npm run build

stress: ## Rapid tab switching against memory / time budgets (ui-preview/src/render/Stress.kt)
	./gradlew -p ui-preview stress

test-roms: ## Unit tests with real ROMs too: ROM_DIR=<folder of your own dumps> (see docs/RELEASING.md)
	@test -n "$(ROM_DIR)" || { echo "ROM_DIR=<folder with your ROMs> is required"; exit 1; }
	POKEDAISY_ROM_DIR="$(ROM_DIR)" ./gradlew :app:testDebugUnitTest

##@ Releasing (docs/RELEASING.md)

version: ## Print the app's versionName / versionCode
	@echo "$(VERSION) (code $(VERSION_CODE))"

bump: ## Set versionName to V=<x.y.z> and versionCode + 1
	@test -n "$(V)" || { echo "usage: make bump V=1.2.0"; exit 1; }
	sed -i.bak -e 's/^\( *versionName = \)".*"/\1"$(V)"/' \
	    -e 's/^\( *versionCode = \)[0-9]*/\1$(shell echo $$(($(VERSION_CODE) + 1)))/' app/build.gradle.kts
	rm -f app/build.gradle.kts.bak
	@$(MAKE) --no-print-directory version

release-apk: ## Signed release APK -> dist/PokeDaisy-<version>.apk (needs keystore.properties)
	@test -f keystore.properties || { echo "keystore.properties is missing: release builds would be unsigned"; exit 1; }
	@test -f app/google-services.json || echo "!! no app/google-services.json: this build can't send crash reports"
	./gradlew :app:assembleRelease
	mkdir -p dist
	cp app/build/outputs/apk/release/app-release.apk $(RELEASE_APK)
	@echo ">> $(RELEASE_APK)"

symbols: ## Upload the release build's native symbols to Crashlytics (after release-apk)
	@test -f app/google-services.json || { echo "needs app/google-services.json"; exit 1; }
	./gradlew :app:uploadCrashlyticsSymbolFileRelease

release: ## Tag v<version> on an up-to-date main and push it: the Release workflow tests, builds and publishes
	@test "$$(git rev-parse --abbrev-ref HEAD)" = main || { echo "release from main"; exit 1; }
	@test -z "$$(git status --porcelain)" || { echo "the working tree isn't clean"; exit 1; }
	git fetch origin main --tags
	@test "$$(git rev-parse HEAD)" = "$$(git rev-parse origin/main)" || { echo "main isn't the same as origin/main"; exit 1; }
	@! git rev-parse -q --verify "refs/tags/v$(VERSION)" >/dev/null || { echo "v$(VERSION) is already tagged: make bump V=... first"; exit 1; }
	git tag -a "v$(VERSION)" -m "PokeDaisy $(VERSION)"
	git push origin "v$(VERSION)"
	@echo ">> pushed v$(VERSION): follow it in the repo's Actions tab (Release workflow)"

release-local: release-apk symbols ## Without CI: build, upload symbols, tag and publish with the gh CLI
	@command -v gh >/dev/null || { echo "needs the GitHub CLI (gh)"; exit 1; }
	git tag -a "v$(VERSION)" -m "PokeDaisy $(VERSION)"
	git push origin "v$(VERSION)"
	gh release create "v$(VERSION)" $(RELEASE_APK) --title "PokeDaisy $(VERSION)" --generate-notes

##@ Looking at the UI

shots: ## Paparazzi screenshots of the companion tabs -> app/src/test/snapshots/images/
	./gradlew :app:recordPaparazziDebug --tests '*ScreenshotTest'

preview: ## Render the UI without the Android SDK (Compose Desktop) -> ui-preview/build/shots/
	./gradlew -p ui-preview render

##@ Headless captures (Docker + libmgba)

capture-image: ## Build the Docker image the capture scripts use
	docker build -t $(CAPTURE_IMAGE) native-capture

menu-shots: ## In-game party/pokedex/items screenshots of every ROM in host_roms.conf (KEYS="a b" to pick) -> native-capture/menu-shots/
	scripts/screenshot_menus.sh $(KEYS)
