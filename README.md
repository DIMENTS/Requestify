# Requestify

A Windows desktop app for streamers that takes Spotify song requests from chat
and lets the streamer review, queue and play them.

Built with Kotlin and Compose Multiplatform, styled with Material 3 Expressive.

## Development

- JBR 21 via Gradle toolchain (auto-downloaded); Gradle itself runs on JDK 17 or newer. Windows 10/11 x64
- Run: `./gradlew run` (one Requestify at a time: quit a running one from its tray menu first)
- Test: `./gradlew jvmTest`
- Installer: `./gradlew packageExe` (`build/compose/binaries/main/exe/`). Start with Windows, the tray's notification
  sender name and the crash restart only work in the installed app; see
  `docs/superpowers/plans/2026-11-18-requestify-plan-4-manual-checks.md`.
- Offscreen renders of UI states: `REQUESTIFY_RENDERS=<folder> ./gradlew jvmTest --tests "*Renders*"`.

Design spec and plans live in [`docs/`](docs/).
