# Shared host composition

The C1-C6 extraction shares the main behaviors. This follow-up gives those behaviors clearer ownership and
removes implementation inheritance from the hosts. Keep sources compiled in place under `app/` and each change
in a separate PR with a chained base, so it can be copied upstream independently.

## D1: Compose settings persistence

Separate the persisted-choice contract from game actions and capabilities. Make `PrefsCompanionSettings` a concrete
implementation of that smaller contract. Android and iOS implement `CompanionSettings` and delegate stored choices.
Use explicit change notifications, a ROM-language provider and a resume-disable veto instead of protected hooks.
Keep game actions required by the host contract; do not introduce dummy implementations to satisfy persistence.

Acceptance: both hosts use delegation; no stored-settings implementation inheritance; stored keys, defaults,
validation, persist-before-notify order, vetoes and live effects remain. Run JVM/Native settings and action tests,
Android/iOS compilation, preview compilation and required quick checks.

Implemented: both hosts now compose a concrete persistence delegate over `StoredCompanionSettings`. Focused
settings/action tests pass on JVM and Native, both platform compilations pass, and the required quick suite passes.
Desktop preview compilation, translations and manual/search checks pass as well.

## D2: Share runtime input and binding ownership

Audit fast-forward, slow-motion and rewind holds, cancellation, binding edits and reloads. Extract cohesive shared
controllers where both hosts currently coordinate the same behavior. Preserve independent input owners, co-op
restrictions, supported actions and installed binding files. Native key delivery and controller discovery stay
platform adapters. Migrate both hosts in the same capability PR and delete the displaced coordination.

Acceptance: releasing one owner cannot cancel another; close/capture transitions clear the appropriate owners;
unsupported choices survive; failed/invalid edits do not publish a reload. Verify behavior through controller
interfaces and targeted native input/capture smoke checks.

Implemented first slice: `GameActions` owns hardware/touch held-action aggregation on both platforms; binding
reloads release the old hardware ownership before replacing parsers. Native effects keep capability/co-op gates.
Five focused tests pass on JVM and Native, including overlapping owners, cancellation and idempotent cleanup.
Second slice implemented: `GameBindings` owns the edit/publish rule on both hosts. Invalid edits and failed
atomic writes do not trigger reloads; a failed D-pad conflict update rolls back the controls edit. Unsupported
hotkey choices remain stored. Four common persistence/failure tests exercise both runtimes. Native smoke
checks remain part of the combined follow-up validation.

## D3: Own session assembly

Move common controller creation, dependencies and shutdown order behind one session owner. Reuse the existing
sampler, playback, options, cheats, battle, actions and state controllers. Platform adapters supply frame dispatch,
core operations and native effects. Preserve generation checks and memory capture between frames. Make lifetime
explicit, so stale work cannot affect a replacement session.

Acceptance: both platforms construct the shared owner; lifecycle and ordering knowledge leaves the hosts; shutdown
is idempotent; stale callbacks and partial startup are tested. Native rendering, audio and frame/input paths retain
their existing ownership and threading requirements.

First slice implemented: evolve the existing playback controller into `GameRuntime`, which owns sampling,
watcher lifetime and common battle sample delivery. Both hosts adopt it; separate sampler fields and duplicate
delivery/teardown are removed. Closed owners reject delayed starts and samples, and iOS startup captures its
own controllers. The focused JVM/Native runtime and sampler tests pass. The next slice also puts option creation, discovery cancellation and final patch cleanup under this owner,
removing the separate iOS options owner. Failed patch cleanup cannot skip queued-work/watcher shutdown.
The action/cheat slice now creates and retires both controllers under the same owner on both hosts. Android's
extra cheat cache and iOS's extra cheat owner are removed; delayed input/imports are inert after close.
Settings callbacks retain their own runtime and native game identity. The state slice binds the existing native
state transports under the same owner. Retired slots reject new work and suppress late callbacks; existing
transactions may finish. Slot/undo tests now run unchanged from common tests on JVM and Native, with lifetime
regressions added. `StateResume` is a synchronous helper with no background lifetime; its native exit-save
adapters remain outside manual-slot retirement. Native smoke checks and the remaining-host audit complete D4.

## D4: Reduce the remaining hosts

Delete setup/teardown displaced by D2-D3. Group Android-only display, permissions, pickers and rendering adapters by
responsibility. Audit remaining iOS code against actual Apple requirements and host capability differences.
Extract further shared orchestration only when it replaces both implementations and reduces caller knowledge.

Acceptance: each remaining responsibility has a named owner and reason to stay platform-specific; no duplicate
portable lifecycle or storage policy remains in the extracted capabilities. Validate current PR heads in CI and
run targeted Android/iPhone/iPad layout, editor, input and lifecycle checks. Prepare a signed build; install only
when paired devices are reachable, with save backup/readback verification. Keep emulators headless and muted.

Line count is evidence of deletion, not the completion criterion. Do not move an iOS-only implementation into a
common folder or introduce forwarding layers merely to shrink a host file. Split a milestone into capability PRs
when necessary; update this plan with evidence and unresolved limitations as each slice completes.


D4 source audit: [shared-host-composition-audit.md](shared-host-composition-audit.md) records the remaining
platform responsibilities and future reductions without claiming an absolute minimum line count. Android now
uses the common picture-fit helper, and iOS setup uses common preferences. Android rewind configuration also
releases common hold ownership before changing capacity. Combined local verification is complete: Android
native save/load/undo, resume/fresh-marker protection and editor/input recovery pass; iPhone/iPad each pass
layout, live editor and library/manual checks; iPhone FF and binding capture/persistence checks pass too. The
signed generic device build succeeds and verifies. Physical installation awaits reachable paired devices.
CI continues on the current PR heads; each capability remains a separate upstream-portable PR.
