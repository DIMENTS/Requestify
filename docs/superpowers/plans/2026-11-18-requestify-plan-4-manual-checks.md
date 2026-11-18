# Plan 4 — controles in een geïnstalleerde versie

Systeemvak, meldingen, starten met Windows en herstarten na een crash werken pas echt in de geïnstalleerde app (de
launcher geeft het pad en de naam). Deze controles doe je zelf; de tests dekken de logica, niet het gedrag van Windows.

## Voorbereiden

1. Sluit een Requestify die nog draait (vanaf nu: **Afsluiten** in het menu van het systeemvak).
2. Bouw de installer in de worktree:

   ```powershell
   .\gradlew.bat packageExe
   ```

3. Installeer `build\compose\binaries\main\exe\Requestify-0.1.0.exe` en start **de geïnstalleerde app** (niet
   `.\gradlew.bat run`). Je eigen gegevens in `%APPDATA%\Requestify` worden gewoon gebruikt.

## Systeemvak

- [ ] Rechtsonder staat het Requestify-icoon (scherp bij jouw schaal; eventueel achter het pijltje).
- [ ] Eén klik op het icoon opent het venster.
- [ ] Het menu (rechtsklik) toont bovenaan de status ("Alles gekoppeld" of het probleem), daaronder Openen en Afsluiten.
- [ ] Het kruisje verbergt het venster; de muziek en de verzoeken lopen door. **Alleen de eerste keer ooit** komt de
      melding "Requestify draait door".
- [ ] Afsluiten in het menu sluit echt af (en pauzeert je Kanaalpunten-beloning, zoals voorheen).
- [ ] Bij een probleem (bijvoorbeeld Spotify uitgelogd) krijgt het icoon een oranje stip, en de tooltip noemt het probleem.

## Eén exemplaar

- [ ] Start Requestify nog een keer (snelkoppeling of de exe): er komt geen tweede, het venster dat al draait komt naar
      voren (ook als het verborgen of geminimaliseerd was).

## Meldingen

- [ ] Een melding heeft als afzender "Requestify" (niet "Java").
- [ ] Klik op een melding over een login of sleutel: het venster opent op **Koppelingen**.
- [ ] Alleen "actie nodig" geeft een melding: een korte verbindingshapering niet.

## Starten met Windows

- [ ] Instellingen → Opstarten → "Starten met Windows" staat **aan** (eerste start van de geïnstalleerde app).
- [ ] Meld je af bij Windows en weer aan: Requestify start **verborgen** in het systeemvak.
- [ ] Taakbeheer → Opstart-apps → Requestify **uitschakelen** → de schakelaar in Instellingen staat uit, met
      "Uitgezet in Taakbeheer". Na een herstart van de app blijft het uit.
- [ ] Zet hem in Instellingen weer aan: Taakbeheer toont hem weer als ingeschakeld.

## Afsluiten van Windows

- [ ] Laat Requestify draaien en start Windows opnieuw op. Na de nieuwe start staat er **geen** regel "Requestify is
      onverwacht gestopt" in het Logboek.

## Crash en herstarten

Alleen voor deze test: start de geïnstalleerde app met een geforceerde fout na 30 seconden.

```powershell
$env:REQUESTIFY_TEST_CRASH_AFTER_S = "30"; & "$env:LOCALAPPDATA\Programs\Requestify\Requestify.exe"
```

(Pas het pad aan als je het programma ergens anders hebt geïnstalleerd, bijvoorbeeld `C:\Program Files\Requestify`.)

- [ ] Na ~30 s verdwijnt de app en start hij **één keer** opnieuw, verborgen, met de melding "Requestify is herstart".
- [ ] In het Logboek staat "Requestify is onverwacht gestopt (laatst actief …) · herstart", met de foutregel in de details.
- [ ] De herstarte app erft de variabele en crasht 30 s later opnieuw: binnen 10 minuten na een herstart → er komt
      **een venster** "Requestify is gestopt door een fout. Start het opnieuw." en **geen** nieuwe herstart.
- [ ] De foutbestanden staan in `%APPDATA%\Requestify\crash`.

Sluit daarna de PowerShell waarin je de variabele zette (of `Remove-Item Env:REQUESTIFY_TEST_CRASH_AFTER_S`).

## Opruimen

- [ ] Wil je weer met `.\gradlew.bat run` werken: verwijder de geïnstalleerde versie via Windows-instellingen → Apps,
      of zet "Starten met Windows" uit (anders start de geïnstalleerde versie bij het aanmelden).
