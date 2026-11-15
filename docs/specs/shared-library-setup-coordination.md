# Shared library and setup coordination

Follow-up to the shared-host composition audit. Preserve sources compiled in place under `app/`, installed
preference formats and each platform's capabilities. Each PR replaces both hosts' repeated decisions.

## E1: Library navigation and dialogs

One `LibraryCoordinator` owns section/help routing, query, modal selection and preference actions. Android's
Settings activity remains an external adapter; iOS uses the same routes inline. Shared search/sort presenters
consume the same controller. Native launcher cursor/media updates remain Android effects, invoked after the
shared preference write. Navigation before Activity startup must not read its unattached preference store.

Acceptance: both hosts use the coordinator and dialog presenter; modal/back ordering and persistence are tested
on JVM and Native; library previews and both platform compilations pass.

## E2: Setup progression

One setup flow owns current step, next/back, completion, busy guards and stale-event rejection. Hosts supply the
steps they actually support and native step-entry/exit effects. Preserve Android's ROM/save/button/media/cover
steps and the iOS ROM/save steps, including RUN SETUP return behavior and first-launch rules.

Acceptance: both hosts use the flow; navigation and busy/completion cases run from common tests. Pickers,
permission requests, scans and native captures stay in their adapters.

## E3: Library projection

Share the construction of available quick views, counts, selected-view fallback and filtered game lists, over
common library preferences and host-supplied platform/category metadata. Reuse it in the Android launcher too.
Preserve supported platform types, hidden/unsupported game filtering and installed IDs.

Acceptance: both hosts use one projection implementation, with tests for category views, favorites, recent,
search and unavailable-view fallback. Rendering and native metadata acquisition retain their existing owners.

## E4: Save-import coordination audit

Consolidate the remaining picker-to-confirmation state and stale-result rules where the two hosts have the same
workflow. Keep URI/Files reads, backup/state storage and platform-specific import capabilities as adapters to the
existing common save transaction. Avoid adding a presenter that merely forwards callbacks.

## Combined validation

Run named JVM/Native tests and the required quick suite per PR. Render focused library/setup previews, and finish
with isolated headless Android/iPhone/iPad library/setup checks. Prepare fresh install candidates; preserve
save/state/binding/preference data on normal simulator installs and install on physical devices when reachable.
Keep each PR independently reviewable with a chained base and register the series in the existing native stack.


## Progress

E1 is replayed onto the cleaned canonical fork chain after the final shared hosts. E2 shares progression on both
hosts through `SetupFlow`, with platform-specific steps and entry/exit effects. Continue E3-E4 as separate layers
on that chain, retaining one implementation to mirror upstream. Historical migration refs are preserved.
