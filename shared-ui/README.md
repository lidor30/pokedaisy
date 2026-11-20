# Shared UI

Android and iOS use the Compose screens, settings, library flows and loaded-game controllers in
`shared-ui/src/commonMain/kotlin`. This module depends on `:shared` for game rules, decoding,
save transactions and emulator contracts. The desktop preview also reads these module directories.

| Change | Implementation | Tests |
| --- | --- | --- |
| Game rule, decoding, save transaction or emulator policy | `shared/src/commonMain/kotlin` | `shared/src/commonTest`, or `shared/src/androidUnitTest` for JVM fixtures |
| Screen, settings action or coordination between the UI and an active game | `shared-ui/src/commonMain/kotlin` | `shared-ui/src/commonTest`; Android screenshots in `app/src/test` |
| Portable emulator code or generated C table | `shared/src/commonMain/cpp` | `shared/src/commonTest/cpp` |

Android Activities supply the platform lifecycle, file access, audio, display, input and JNI transports.
The Swift entry point calls the Apple adapters in `shared-ui/src/iosMain` and `shared/src/iosMain`
for UIKit, audio, controllers and the native core. A shared feature can receive a platform operation
through its existing host interface. For example, `GameRuntime` owns the loaded game's controllers
on both platforms, while each host schedules work against its emulator.

The [development guide](../docs/DEVELOPMENT.md#the-shared-module-shared-android-and-ios) covers builds
and test commands. The [host composition audit](../docs/specs/shared-host-composition-audit.md) records
the migration of settings, input and game lifetime ownership.
