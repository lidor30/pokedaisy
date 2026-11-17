# Shared host composition audit

Audited after D1-D3 on 15 November 2026. Sources remain compiled in place under `app/`.
The shared module directory describes a compilation target, not where every shared source must live.

## Ownership after the cleanup

```text
Android Activity / engine                   iOS host / GameViewController
  native input + frame/state transport        Apple input + frame/state transport
                  \                         /
                   GameRuntime
                     playback/watchers + queued GameSession work
                     sampler + battle sample delivery
                     options + discovery lifetime
                     actions + held-input ownership
                     cheats + retired-edit guards
                     state slots + retired-transport guards

shared settings -> concrete PrefsCompanionSettings delegate -> PreferenceStore
shared controls -> GameBindings -> atomic files -> native reload adapters
shared screen   -> GameScreenState / GameScreen / fittedGamePicture
```

The settings hosts use composition and Kotlin interface delegation. The persistence implementation has no
runtime superclass or dummy game actions. Native implementations supply effects and capabilities. The runtime
has one loaded-ROM lifetime; Android can stop/restart its core without retiring that lifetime.

Close stops producers, releases holds and retires edits/slots, then cleans options, battle commands, queued
work and watchers. Every cleanup runs even when an earlier cleanup fails. Runtime, sampler, telemetry and slot
transport guards reject stale work at their respective seams. Native state transactions already in progress
can finish. Automatic resume remains a synchronous transaction helper with native save/achievement/rejection
adapters. It is deliberately separate from retiring manual slot requests.

## Why the hosts still contain Kotlin

Platform composition includes attaching shared controllers to native transports. Those calls can be ordinary
Kotlin even when they belong to a platform host. A useful extraction removes repeated policy or caller ordering
knowledge on both platforms. The D1-D3 changes share those policies and lifetimes; they do not make every
composition site platform independent.

| Remaining iOS file | Responsibility and reason for its placement |
| --- | --- |
| `IosPreferences` | The NSUserDefaults implementation of the common key-value store; installed defaults remain compatible. |
| `IosHosts` | Bundle fonts/version, Skia decoding and system date formatting; installs common theme, manual cache and cheat parser adapters. |
| `IosTouchPreferences` | GCController notifications and Apple haptics, plus observable state for those native signals. |
| `IosTouchControls` | Connects shared controls/editor to native key sets and native pause/resume. Editor gestures and layout storage are shared. |
| `IosGameScreen` | Binds the common screen to an Apple controller, focus ownership and polled controller input. Layout frames/picture fitting and panel gestures are common. |
| `IosCompanionSettings` | Declares the iOS feature set and connects shared settings to runtime/native effects. Unsupported Android features retain explicit capability values. |
| `IosLibraryHost` | Binds common library/setup/settings presenters to document pickers, app URLs and the installed iOS library storage format. |
| `PokeDaisyHost` | Swift entry points and native core/state/render transport assembly; runtime controller lifetime is common. |
| `IosUiTesting` | Native UI automation configuration. |

The nine files total 1,396 lines including imports, comments, capability declarations and composition. This is
not a claim of a mathematical minimum or a 300-400-line native shell. Reaching a 300-400-line shell would also require consolidating capability declarations and library/game host
assembly. These remain potential future reductions. Their next extraction should replace both platforms' wiring and
preserve Android's DS, co-op, achievements, display and renderer capabilities.

Android retains Activity lifecycle/intents, display/fold/permission handling, native GL/DS/HUD/input windows,
audio/frame scheduling, achievement capture and co-op guards. Their different transports and capabilities are
supplied to the same common controllers. The Activity remains large; moving native callbacks into forwarding
classes would not by itself reduce its callers' ordering knowledge. A later split can group those native
responsibilities around their actual lifetimes.

## Performance and native cleanup

Rendering/audio delivery, native frame buffers and touch-key delivery retain their existing fast paths.
No frame copies, coroutine hops or new locks are added to those paths. Android reads its cached action
controller; ownership bookkeeping runs only on action edges. Sampling retains core-thread capture, one worker
image at a time and generation-checked delivery.

Android's stop wait is bounded. Its engine therefore retains native core destruction/patch cleanup; retiring
portable options does not unpatch from the Activity thread. iOS restores its patches between display callbacks.
Native patch operations retain the core write lock. The common runtime owns discovery cancellation and portable
retirement on both platforms.

## Verification

D1-D3 have named JVM and Native tests, Android/iOS compilation, the required quick suite and focused preview
compilation. Tests cover independent input owners, invalid/failed binding writes, replacement callbacks,
cleanup failures, retired cheat edits and late state transport callbacks. Existing state/undo tests now run on
both runtimes. Translation and manual/search checks pass for each capability slice.

The D4 branch also replaces Android's remaining manual picture-fit calculation with the common helper already
used by iOS, adopts common setup persistence on iOS and removes stale native imports. Android's rewind setting
quarantines shared held-action ownership before changing capacity, matching iOS.

Combined local verification passes:

| Check | Result |
| --- | --- |
| Android native state transactions | Original diagnostic cartridge verifies save/load/undo through actual core frames. |
| Android native automatic resume | Background/process resume and fresh-marker interruption protection pass. |
| Android shared editor | BACK/DONE close correctly; game input recovers. Preferences, bindings and diagnostic state files are restored. |
| iPhone and iPad | Each passes portrait/landscape, live editor in both orientations, and library/manual lifecycle checks in an isolated app. |
| iPhone touch FF | Actual core speed follows tap toggles and hold release. |
| iPhone binding capture | Cancel/clear and hotkey persistence across relaunch pass. |
| Device packaging | Generic iPhone/iPad build succeeds and its signature verifies. |

The final required quick suite, APK assembly, targeted geometry tests, platform compilation, preview, translations
and manual/search checks pass. CI runs common UI tests on JVM as well as Native, and Make supports focused
`MODULE=shared-ui` tests. CI runs independently on each PR head; its live result belongs to the PR checks.
Physical installation remains conditional on paired-device availability, with save backup/readback before and
after installation. Simulators remain headless and muted.
