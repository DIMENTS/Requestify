# Development

- JetBrains Runtime 21 through the Gradle toolchain (downloaded automatically); Gradle itself runs on JDK 17 or newer.
  Windows 10/11 x64.
- Run: `.\gradlew.bat run` (one Requestify at a time: quit a running one from its tray menu first).
- Test: `.\gradlew.bat jvmTest`.
- Installers: `.\gradlew.bat packageRequestifyExe` and `packageRequestifyMsi` (`build/installer/`). A local build gets
  a fresh version `0.0.<n>` every time and never updates itself; `-Prequestify.version=0.2.0` builds a release version.
  `packaging/check-msi.ps1 <msi>` lists the installer's own actions.
- Start with Windows, the tray, the crash restart, updating and uninstalling only work in the installed app; see the
  manual checklists in `superpowers/plans/`.
- To be offered pre-releases as updates while testing: `REQUESTIFY_UPDATE_PRERELEASES=1`.
- Offscreen renders of UI states: `REQUESTIFY_RENDERS=<folder> .\gradlew.bat jvmTest --tests "*Renders*"`.
- The README screenshots (`images/`, Dutch and English):
  `REQUESTIFY_SCREENSHOTS=docs/images .\gradlew.bat jvmTest --tests "*ReadmeScreenshots*" --rerun`.

Design specs and plans live in [`superpowers/`](superpowers/). Third-party material is listed in [`NOTICE`](../NOTICE).

## Releasing

1. Push a tag with the new version, for example `git tag v0.2.0` and `git push origin v0.2.0`. Digits only:
   `v<MAJOR>.<MINOR>.<BUILD>`, and every number only once.
2. GitHub Actions runs the tests, builds the installers and creates a **draft release** with the files and their
   checksums.
3. Write what's new in the draft and publish it. Publish a test build as a **pre-release**: it is never offered as an
   update.
