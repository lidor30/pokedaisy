# Testing and releasing

Two GitHub Actions workflows guard `main` and the releases:

| Workflow | Runs on | What it does |
| --- | --- | --- |
| **CI** (`.github/workflows/ci.yml`) | every PR, every push to `main` | translations check, all JVM unit tests, the debug APK (Kotlin + native mGBA/rcheevos build), `ui-preview`'s tab-switch `stress` budgets (which compile it) and docs search check; no screen render (`make preview` locally) |
| **Release** (`.github/workflows/release.yml`) | a pushed `v<versionName>` tag | checks the tag, runs CI again on that commit, runs the ROM-backed tests on your ROM runner, builds and signs the APK (arm64 and 32-bit ARM in one), checks the signing key and that both ABIs' libraries are in it, uploads native symbols to Crashlytics and puts the GitHub release up as a **draft**. Publishing that draft (you) starts the **Website** workflow for the tag (not for a pre-release) |

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

Then follow the **Release** run in the repo's Actions tab. Its last job waits for you:
**Review deployments → release → Approve**. When it's green the release is on GitHub as a
**draft**, with `PokeDaisy-1.2.0.apk` attached. Only you see it. Edit its notes, add any other
platform's files (`gh release upload v1.2.0 <files>`: the Linux packages, the 3DS `.3dsx`; the
APK stays the release's first `.apk`), then publish it: **Publish release** on its page, or
`gh release edit v1.2.0 --draft=false`. Publishing starts the **Website** workflow (a full
release only), and the in-app updater offers the version on the next library open.

- Releases are immutable once published: a broken APK can't be swapped, nor a file added,
  only followed by a new version. Everything goes onto the draft.

- A version with a suffix (`1.2.0-rc.1`) goes out as a **pre-release**, which the
  in-app updater skips. Use that for test builds.
- If a run fails, nothing is published. Fix it on `main`, delete the tag
  (`git push --delete origin v1.2.0 && git tag -d v1.2.0`), and run `make release`
  again.
- Without CI (e.g. GitHub Actions down): `make release-local` builds, signs, uploads
  the symbols and puts the release up as a draft with the `gh` CLI from your Mac; publish it
  the same way. Its tag push starts the Release workflow too: cancel that run (`gh run cancel`)
  rather than approve it.

## The Linux files (beta)

The **Linux foundation** workflow (`.github/workflows/linux.yml`) builds them; the Release workflow doesn't. It runs on
pushes that touch Linux code, so start it on the tag yourself once the draft is up, then add its files to the draft:

```bash
V=1.2.0
gh workflow run linux.yml --ref v$V        # the tagged commit; about 25 minutes
gh run watch "$(gh run list --workflow linux.yml --branch v$V --limit 1 --json databaseId --jq '.[0].databaseId')"
gh run download "$(gh run list --workflow linux.yml --branch v$V --limit 1 --json databaseId --jq '.[0].databaseId')" -D linux-v$V
ls linux-v$V/*/*.zip                        # the six files below
gh release upload v$V linux-v$V/*/*.zip
```

| File | For |
| --- | --- |
| `pokedaisy-linux-x86_64.zip` | Steam Deck and desktop Linux (glibc 2.35 or newer): the executable and its two cores |
| `pokedaisy-linux-aarch64.zip` | TrimUI devices, as an app |
| `pokedaisy-portmaster-aarch64.zip` | PortMaster handhelds (`PokeDaisy.sh` + `pokedaisy/`) |
| `pokedaisy-libretro-x86_64.zip`, `pokedaisy-libretro-aarch64.zip` | the three libretro cores (GBA on armGBA, GB/GBC on mGBA, DS on DSperate) and their licenses, for other frontends |
| `PokeDaisy-NXRedux.zip` | the TrimUI Brick on NXRedux (all three cores behind one collection) |

The install guide for all six is the manual's Linux page (`docs/manual/linux.md`); link it in the release notes and
say the Linux files are a **beta** (Android stays the full app). A file missing from the run means a job failed: fix
it on `main`, then follow the "If a run fails" steps above.

## One APK for arm64 and 32-bit ARM

The release APK carries the native code for both arm64 (the Thor, the Retroid Pocket, phones) and 32-bit ARM
(`-Ppokedaisy.armv7=true`), so a device running a 32-bit Android (a Galaxy Tab A SM-T510, older tablets) installs
the same `PokeDaisy-<version>.apk` as everyone else, from the website, the release page or the in-app updater
(every updater, old ones too). The cost is the 32-bit libraries: about 5 MB more in every APK (they're stored
uncompressed, so Android maps them straight from the APK). One APK per ABI was tried and dropped (the owner's call,
2026-11-08): players would have had to find the right file, and updaters before it take the first `.apk` of a
release. The 32-bit build has no melonDS JIT (melonDS has none for 32-bit ARM), so DS games run on its interpreter
there. Debug builds stay arm64 only unless they pass the flag.

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

### Collaborators with write access

A repo owned by a personal account has one collaborator level: write. Write lets
someone push branches, tags and releases and run any workflow, so these are set up
(2026-10-25) to leave them only branches and PRs that you approve:

| Rule | What it stops |
| --- | --- |
| Ruleset **Review before merge (main, dev)**: a PR with 1 approval, the code owner's (`.github/CODEOWNERS`: you), approvals dropped on a new push, the last pusher can't approve | merging, or pushing to `main` / `dev`, without you |
| Tag ruleset **Only the owner makes tags**: every tag, create / update / delete restricted | starting a release (`v*`), or a release on a new tag of any name |
| `release` environment: `v*` tags only, **you as required reviewer** | the signing key and `google-services.json` reaching anything but your release. Each release waits for one click (the run's **Review deployments**) |
| `website` environment: `v*` tags only, holding `FIREBASE_SERVICE_ACCOUNT_POKEDAISY` | a workflow on a pushed branch reading the Firebase key (a repo-level secret would reach it). So there are no PR preview channels |
| **Immutable releases** (Settings → General → Releases) | anyone changing or deleting a published release's APK or tag. Only releases published after it was turned on |
| The ROM runner's job check (section 3) | a pushed branch's workflow running on your Mac |

You bypass both rulesets (the repository admin role), so pushing to `dev` and
`make release` work as before. A collaborator can still push their own branches, run CI
on them, and triage issues and PRs.

The Firebase key moves into the `website` environment once by hand (a secret can't be
read back, so make a new key: Firebase console → Project settings → Service accounts →
Generate new private key):

```bash
gh secret set FIREBASE_SERVICE_ACCOUNT_POKEDAISY --env website < ~/Downloads/pokedaisy-firebase-key.json
gh secret delete FIREBASE_SERVICE_ACCOUNT_POKEDAISY      # the repo-level copy
rm ~/Downloads/pokedaisy-firebase-key.json
```

Then delete the old key in Google Cloud (IAM → Service accounts → the Firebase
service account → Keys).

### 2. The `release` environment: signing key and Firebase config

The release job reads its secrets from an environment called `release`:

1. Go to **Settings → Environments → New environment**, and name it `release`.
2. Under **Deployment branches and tags**, choose "Selected", then add the tag rule `v*`.
3. Add yourself as a **Required reviewer**. Each release then waits for your click
   before the key is used (on since 2026-10-25, see "Collaborators with write access").

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
3. Install the job check, so the runner only takes your releases (anyone with write
   access could otherwise push a branch whose workflow asks for this runner):
   ```bash
   cp scripts/runner_job_check.sh ~/actions-runner/job_check.sh
   chmod +x ~/actions-runner/job_check.sh
   echo "ACTIONS_RUNNER_HOOK_JOB_STARTED=$HOME/actions-runner/job_check.sh" >> ~/actions-runner/.env
   ```
   It fails any job that isn't the Release workflow on a `v*` tag you pushed. Restart the
   runner after changing `.env`.
4. Turn the job on: `gh variable set ROM_RUNNER --body true`.
   - Until you do, releases skip the ROM tests, and the release notes say so.
   - Once it's on, a release **waits** for the runner, so it must be online.

**Security:** a self-hosted runner on a public repo runs whatever workflow reaches it.
This setup keeps that narrow:

- Only the Release workflow targets it, and only tags you can push trigger it (the
  tag ruleset above). The job check (step 3) refuses anything else that asks for it.
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
