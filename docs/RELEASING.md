# Testing and releasing

Two GitHub Actions workflows guard `main` and the releases:

| Workflow | Runs on | What it does |
| --- | --- | --- |
| **CI** (`.github/workflows/ci.yml`) | every PR, every push to `main` | translations check, all JVM unit tests, the debug APK (Kotlin + native mGBA/rcheevos build), `ui-preview`'s tab-switch `stress` budgets (which compile it) and docs search check; no screen render (`make preview` locally) |
| **Release** (`.github/workflows/release.yml`) | a pushed `v<versionName>` tag | checks the tag, runs CI again on that commit, runs the ROM-backed tests on your ROM runner, builds and signs the APK, checks the signing key, uploads native symbols to Crashlytics, publishes the GitHub release, then starts the **Website** workflow for that tag (not for a pre-release) |

`make check` runs the same thing as CI locally. For the everyday loop, `make test-quick` (about a minute) runs the
unit tests without the Paparazzi renders and `BestEffortTest`, its forks side by side; `make test-quick
T='*MoneyTest'` runs one class.

## Day to day: releasing a version

```bash
git checkout main && git pull
make bump V=1.2.0          # versionName 1.2.0, versionCode + 1
# + the version's WHAT'S NEW in app/.../companion/WhatsNew.kt (a few lines; WhatsNewTest fails without them)
git commit -am "Version 1.2.0" && git push   # or through a PR, like any change
make release               # tags v1.2.0 on main and pushes the tag
```

Then follow the **Release** run in the repo's Actions tab. The release shows up on
GitHub when it's green, with `PokeDaisy-1.2.0.apk` attached, and the in-app updater
offers it on the next library open.

- A version with a suffix (`1.2.0-rc.1`) goes out as a **pre-release**, which the
  in-app updater skips. Use that for test builds.
- If a run fails, nothing is published. Fix it on `main`, delete the tag
  (`git push --delete origin v1.2.0 && git tag -d v1.2.0`), and run `make release`
  again.
- Without CI (e.g. GitHub Actions down): `make release-local` builds, signs, uploads
  the symbols and publishes with the `gh` CLI from your Mac. A release published that way
  fires the Website workflow by itself.

## One-time setup

### 1. Make the PR checks required

The CI workflow runs on every PR, but GitHub only blocks merging once the checks are
required. Run CI once first (open any PR) so the check names exist, then:

1. **Settings → Rules → Rulesets → New ruleset → New branch ruleset**.
2. Name it `main`, set Enforcement to **Active**, and under Target branches add the
   **default branch**.
3. Turn on:
   - **Require a pull request before merging**.
   - **Require status checks to pass**, then add `Unit tests + debug build` and
     `UI preview + stress`. Also tick **Require branches to be up to date**.
   - **Block force pushes**.
4. Optional: add yourself under **Bypass list** if you want to push small fixes
   straight to `main`.

Protect the release tags too, so only you can trigger a release:

- **New tag ruleset**, target `v*`, with **Restrict creations**, **Restrict updates**
  and **Restrict deletions** on, and yourself in the bypass list.

Fork PRs: in **Settings → Actions → General**, set "Approval for running fork pull
request workflows" to **Require approval for all external contributors**.

### 2. The `release` environment: signing key and Firebase config

The release job reads its secrets from an environment called `release`:

1. Go to **Settings → Environments → New environment**, and name it `release`.
2. Under **Deployment branches and tags**, choose "Selected", then add the tag rule `v*`.
3. Optional: add yourself as a **Required reviewer**. Each release then waits for your
   click before the key is used.

Add these **environment secrets**. With the GitHub CLI, from the repo folder on your
Mac:

```bash
base64 -i ~/.android/pokedaisey-release.jks | gh secret set KEYSTORE_BASE64 --env release
gh secret set KEYSTORE_PASSWORD --env release     # paste storePassword from keystore.properties
gh secret set KEY_ALIAS --env release --body pokedaisey
gh secret set KEY_PASSWORD --env release          # paste keyPassword
gh secret set GOOGLE_SERVICES_JSON --env release < app/google-services.json
gh secret set SCREENSCRAPER_DEV_ID --env release        # ScreenScraper developer login
gh secret set SCREENSCRAPER_DEV_PASSWORD --env release
```

| Secret | What it is |
| --- | --- |
| `KEYSTORE_BASE64` | the release keystore (`~/.android/pokedaisey-release.jks`), base64 |
| `KEYSTORE_PASSWORD` / `KEY_PASSWORD` | the two passwords from your `keystore.properties` |
| `KEY_ALIAS` | `pokedaisey` (the key predates the rename) |
| `GOOGLE_SERVICES_JSON` | the Firebase project's `google-services.json` (crash reports). Optional: without it the release builds without crash reporting |
| `SCREENSCRAPER_DEV_ID` / `SCREENSCRAPER_DEV_PASSWORD` | the app's ScreenScraper developer account (asked for on screenscraper.fr's forum), sent with every API call next to the player's own login. Locally: `screenscraper.devId` / `screenscraper.devPassword` in `local.properties`. Optional for the build, but without them ScreenScraper may refuse the app |

Also add one **repository variable**, so a release signed with any other key fails
instead of stranding users. **Every release must use the same key**, or the in-app
update won't install.

```bash
keytool -list -v -keystore ~/.android/pokedaisey-release.jks -alias pokedaisey | grep SHA256
gh variable set RELEASE_CERT_SHA256 --body "AB:CD:…"   # the SHA256 line's value
```

Keep a backup of the keystore outside the repo and outside GitHub. A secret can't be
read back, only replaced.

### 3. The ROM runner: full testing before a release

Many unit tests read real ROMs: ROM art, the trainer card, region maps, guides,
Pokédexes and the hacks' tables. ROMs can't be in this public repo, and they
shouldn't go to GitHub's machines either. So those tests skip themselves on CI
(`Assume`), and the release runs them on **your Mac**, as a self-hosted runner that
already has your ROMs.

What it needs:

- **ROMs only.** No save files: the saves and memory dumps the tests use are already
  committed as fixtures (`app/src/test/resources/fixtures/`). No decomp builds either:
  only the generator scripts and `ui-preview` use `$DECOMPS`, not the tests.
- Your ROMs, named the way the tests expect. The tests read
  `~/Downloads/Game ROMs & Emulation/gba/` on your Mac by default (every language's GBA carts), and from beside it `gbc/`
  and `nds/`, so point
  `POKEDAISY_ROM_DIR` at the `gba/` folder of that same layout.
  Any test whose ROM isn't there still skips rather than fails. Check which ones ran
  with `make test-roms ROM_DIR=<folder>` and the report at
  `app/build/reports/tests/testDebugUnitTest/index.html`.

Set it up once:

1. Go to **Settings → Actions → Runners → New self-hosted runner → macOS**, and follow
   the download / `./config.sh` steps it shows.
   - When `config.sh` asks for labels, add `pokedaisy-roms`.
   - Install it somewhere outside this repo, e.g. `~/actions-runner`.
2. In the runner's folder, create `.env` with what the build needs:
   ```
   POKEDAISY_ROM_DIR=/Users/<you>/Downloads/Game ROMs & Emulation/gba
   ANDROID_HOME=/Users/<you>/Library/Android/sdk
   JAVA_HOME=/Library/Java/JavaVirtualMachines/<jdk-17>/Contents/Home
   ```
3. Turn the job on: `gh variable set ROM_RUNNER --body true`.
   - Until you do, releases skip the ROM tests, and the release notes say so.
   - Once it's on, a release **waits** for the runner, so it must be online.

**Security:** a self-hosted runner on a public repo runs whatever workflow reaches it.
This setup keeps that narrow:

- Only the Release workflow targets it, and only tags you can push trigger it (the
  tag ruleset above).
- CI never uses it. Fork PRs need your approval to run anything (step 1).
- Simplest of all: don't run it as a service. Start it with `./run.sh` when you
  release, and stop it after (Ctrl+C). The ROM job waits in the queue until it's up.

### 4. Crash reports (Firebase Crashlytics)

- **On your Mac:** put the Firebase project's `google-services.json` in `app/`. It's
  gitignored. With it, release builds can send crash reports and `make symbols`
  uploads the native symbols.
- **Without it** (forks, PR builds), the app builds the same, with crash reports
  unavailable: the Settings row and the one-time ask don't show.
- **Collection is off by default.** The manifest turns it off, and only the player's yes
  turns it on: Settings > CRASH REPORTS, or the one-time ask in the Library on the
  third app open, at least 48 h after install. Turning it off drops any report not yet
  sent.
- **Native crashes** (mGBA, the JNI bridge) are reported through `crashlytics-ndk`. They
  only read as function names after `uploadCrashlyticsSymbolFileRelease`, which the
  Release workflow runs.
