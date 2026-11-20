# Shared UI

This module compiles the common Compose screens and their models for both Android and iOS.
Shared screens and models live in `src/commonMain`. Android links this module and the iOS app
links the framework it builds.

`src/iosMain` contains the Apple platform adapters and the remaining host orchestration being
consolidated into common implementations used by both platforms.

Both hosts now use common preferences, companion settings, game actions, cheats, option patches, battle input,
sampling, playback policy, ROM identity, save importing, library models, state slots, automatic resume and the
offline manual cache. Screen preferences and theme storage also have one implementation. Platform entry points supply native effects
and capabilities to those models.

The [host composition plan](../docs/specs/shared-host-composition.md) and
[remaining-host audit](../docs/specs/shared-host-composition-audit.md) describe controller ownership, native
transports and the remaining composition sites. Settings persistence uses a concrete delegate; `GameRuntime`
owns the loaded game's controller lifetime on both platforms.
