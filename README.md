# Requestify

[Nederlands](#nederlands) · [English](#english)

## Nederlands

Requestify is een Windows-app voor streamers. Kijkers vragen nummers aan via de Twitch-chat, Kanaalpunten en donaties
(StreamElements of DonoLink), en Requestify zet ze in je Spotify-wachtrij. Jij houdt het overzicht: wachtrij, regels,
blacklist en een logboek van alles wat er binnenkwam. Gratis en open source (MIT).

### Installeren

1. **Eerst even checken: Slimme app-beheer** (alleen Windows 11). Open *Windows-beveiliging* → *App- en browserbeheer* →
   *Slimme app-beheer*. Staat dat op **Aan**, dan blokkeert Windows Requestify, omdat het nog niet ondertekend is. Staat
   het op **Uit** of **Evaluatie**, dan kun je verder. Staat het aan? Laat het even weten.
2. Download **Requestify-Setup-*versie*.exe** bij de [nieuwste release](https://github.com/DIMENTS/Requestify/releases/latest).
3. Open het bestand. Windows laat eerst **"Windows heeft uw pc beschermd"** zien: klik op **Meer info** en daarna op
   **Toch uitvoeren**. Dat komt doordat Requestify nog niet ondertekend is, en gebeurt alleen bij deze eerste installatie.
4. Requestify installeert voor jouw Windows-gebruiker, zonder beheerdersvraag, en start meteen met een welkomstwizard
   die je helpt Spotify, Twitch en je donaties te koppelen.

### Daarna

- Requestify start met Windows en wacht in het systeemvak tot je live gaat.
- Het werkt zichzelf bij via GitHub, maar **nooit tijdens een stream**: een nieuwe versie wordt geïnstalleerd bij de
  volgende start, of als je offline op **Nu bijwerken** klikt (*Instellingen* → *Over Requestify*).
- Verwijderen gaat via *Windows-instellingen* → *Apps*. Je koppelingen en instellingen blijven bewaard, dus opnieuw
  installeren zet alles terug.

### Uitbrengen (voor de maker)

1. Zet een tag met het nieuwe versienummer, bijvoorbeeld `git tag v0.2.0` en `git push origin v0.2.0`. Alleen cijfers:
   `v<MAJOR>.<MINOR>.<BUILD>`, en elk nummer maar één keer.
2. GitHub Actions draait de tests, bouwt de installers en maakt een **concept-release** met de bestanden en hun
   controlegetallen.
3. Schrijf in het concept wat er nieuw is en publiceer het. Een testversie publiceer je als **pre-release**: die krijgen
   anderen niet als update.

## English

Requestify is a Windows app for streamers. Viewers request songs through Twitch chat, Channel Points and donations
(StreamElements or DonoLink), and Requestify puts them in your Spotify queue. You stay in control: queue, rules,
blacklist and a log of everything that came in. Free and open source (MIT).

### Installing

1. **First, check Smart App Control** (Windows 11 only). Open *Windows Security* → *App & browser control* →
   *Smart App Control*. If it is **On**, Windows blocks Requestify, because it is not signed yet. If it is **Off** or
   **Evaluation**, you're good to go. Is it on? Let us know.
2. Download **Requestify-Setup-*version*.exe** from the [latest release](https://github.com/DIMENTS/Requestify/releases/latest).
3. Open it. Windows first shows **"Windows protected your PC"**: click **More info**, then **Run anyway**. That is
   because Requestify is not signed yet, and only happens on this first install.
4. Requestify installs for your Windows user, without an admin prompt, and starts with a welcome wizard that helps you
   connect Spotify, Twitch and your donations.

### Afterwards

- Requestify starts with Windows and waits in the system tray until you go live.
- It updates itself from GitHub, but **never during a stream**: a new version installs at the next start, or when you
  click **Update now** while offline (*Settings* → *About Requestify*).
- Uninstall through *Windows Settings* → *Apps*. Your connections and settings are kept, so reinstalling brings
  everything back.

### Releasing (for the maintainer)

1. Push a tag with the new version, for example `git tag v0.2.0` and `git push origin v0.2.0`. Digits only:
   `v<MAJOR>.<MINOR>.<BUILD>`, and every number only once.
2. GitHub Actions runs the tests, builds the installers and creates a **draft release** with the files and their
   checksums.
3. Write what's new in the draft and publish it. Publish a test build as a **pre-release**: it is never offered as an
   update.

## Development

- JetBrains Runtime 21 through the Gradle toolchain (downloaded automatically); Gradle itself runs on JDK 17 or newer.
  Windows 10/11 x64.
- Run: `.\gradlew.bat run` (one Requestify at a time: quit a running one from its tray menu first).
- Test: `.\gradlew.bat jvmTest`.
- Installers: `.\gradlew.bat packageRequestifyExe` and `packageRequestifyMsi` (`build/installer/`). A local build gets
  a fresh version `0.0.<n>` every time and never updates itself; `-Prequestify.version=0.2.0` builds a release version.
  `packaging/check-msi.ps1 <msi>` lists the installer's own actions.
- Start with Windows, the tray, the crash restart, updating and uninstalling only work in the installed app; see the
  manual checklists in `docs/superpowers/plans/`.
- To be offered pre-releases as updates while testing: `REQUESTIFY_UPDATE_PRERELEASES=1`.
- Offscreen renders of UI states: `REQUESTIFY_RENDERS=<folder> .\gradlew.bat jvmTest --tests "*Renders*"`.

Design specs and plans live in [`docs/`](docs/). Third-party material is listed in [`NOTICE`](NOTICE).
