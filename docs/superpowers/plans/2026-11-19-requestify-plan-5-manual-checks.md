# Plan 5 — handmatige checks (uitbrengen aan vrienden)

Deze checks gaan over wat alleen een geïnstalleerde versie kan laten zien: installeren, de welkomstwizard, bijwerken via
GitHub en verwijderen. Doe ze in deze volgorde. Kloppen een stap niet, noteer dan wat je zag en ga door met de rest.

Alle commando's zijn voor PowerShell, vanuit de projectmap.

## A. Voorbereiding

1. **Oude versie weg.** Je huidige Requestify 0.1.0 staat nog *per computer* in `C:\Program Files\Requestify`. Die
   verwijder je één keer: *Instellingen* → *Apps* → *Requestify* → *Verwijderen*. (Vanaf nu installeert Requestify per
   gebruiker; die twee zitten elkaar anders in de weg.)
   - [ ] `C:\Program Files\Requestify` bestaat niet meer.
2. **Slimme app-beheer.** Volg de check uit de README op je eigen pc: *Windows-beveiliging* → *App- en browserbeheer* →
   *Slimme app-beheer*.
   - [ ] Staat op Uit of Evaluatie (anders: eerst melden, dan stoppen).

## B. Een lokale installatie

3. **Bouwen:** `.\gradlew.bat packageRequestifyExe`. Het bestand staat in `build\installer\exe\`.
   - [ ] De naam is `Requestify-0.0.<getal>.exe`; een tweede build later heeft een ander getal.
4. **Installeren:** dubbelklik het bestand.
   - [ ] Geen beheerdersvraag (UAC) en geen keuzes.
   - [ ] Requestify start na de installatie vanzelf.
   - [ ] Geïnstalleerd in `%LOCALAPPDATA%\Requestify`; Start-menu en bureaublad hebben een snelkoppeling.
   - [ ] Je bestaande koppelingen zijn er nog (je data in `%APPDATA%\Requestify` bleef staan) en je krijgt **geen**
     welkomstwizard, want je had Requestify al ingesteld.
5. **Over Requestify:** *Instellingen* → onderaan.
   - [ ] Versie `0.0.<getal>`, en geen "Zoeken naar updates" (een lokale build werkt zichzelf nooit bij).

## C. De welkomstwizard van nul af

6. Sluit Requestify af (systeemvak → *Afsluiten*). Hernoem `%APPDATA%\Requestify` naar `Requestify-bewaard` en start
   Requestify opnieuw.
   - [ ] De welkomstwizard verschijnt: "Hé! Welkom bij Requestify".
   - [ ] Spotify koppelen werkt vanuit de wizard; na het inloggen: vinkje, confetti, het Spotify-logo vliegt naar boven.
   - [ ] "Later doen" gaat door naar de volgende kaart; de Klaar-kaart laat zien wat er nog kan.
   - [ ] Venster halverwege sluiten en Requestify opnieuw openen: de wizard gaat verder waar hij was.
   - [ ] Zet in Windows *Instellingen* → *Toegankelijkheid* → *Visuele effecten* → *Animatie-effecten* uit, start
     Requestify opnieuw (via *Instellingen* → *Over Requestify* → *Welkomstwizard openen*): geen confetti of beweging,
     de kaarten wisselen rustig. Zet het daarna weer aan.
7. Sluit Requestify af, verwijder de nieuwe `%APPDATA%\Requestify` en hernoem `Requestify-bewaard` terug.

## D. De eerste echte release

8. Na het pushen naar GitHub (dat vraag ik je apart): `git tag v0.2.0` en `git push origin v0.2.0`.
   - [ ] De workflow *Release* op GitHub slaagt en maakt een **concept-release** met `Requestify-Setup-0.2.0.exe`,
     `Requestify-0.2.0.msi` en `SHA256SUMS.txt`.
9. Schrijf wat er nieuw is en publiceer de release (géén pre-release).
10. Verwijder je lokale build (*Instellingen* → *Apps*), download `Requestify-Setup-0.2.0.exe` van de releasepagina en
    installeer die.
    - [ ] Eerst "Windows heeft uw pc beschermd" → *Meer info* → *Toch uitvoeren*; daarna installeert hij zonder vragen.
    - [ ] Starten met Windows staat weer zoals het was (het verwijderen had het opgeruimd en onthouden).

## E. Bijwerken van 0.2.0 naar 0.2.1

11. Maak een kleine wijziging, zet tag `v0.2.1`, publiceer die release.
12. Start Requestify (0.2.0) en wacht ruim 2 minuten.
    - [ ] *Over Requestify*: "Versie 0.2.1 staat klaar", en een stipje bij *Instellingen*. Geen melding, geen balk.
13. **Niet tijdens een stream:** ga live (of open de verzoeken).
    - [ ] "Nu bijwerken" is uitgeschakeld, met de uitleg eronder.
14. **Herstart midden in een stream:** sluit Requestify af terwijl je live bent en start hem binnen 10 minuten opnieuw.
    - [ ] Hij installeert de update níet bij die start (Requestify blijft 0.2.0).
15. Ga offline, sluit de verzoeken en klik op **Nu bijwerken**.
    - [ ] Requestify sluit, installeert zonder vragen en start vanzelf weer op.
    - [ ] Het kaartje "Bijgewerkt naar 0.2.1" staat op Home met de tekst van de release; na sluiten komt het niet terug.
    - [ ] Het Logboek heeft "Bijgewerkt van 0.2.0 naar 0.2.1".
    - [ ] Starten met Windows staat nog aan (een update laat dat met rust).
16. **Pre-release:** publiceer `v0.2.2` als *pre-release*.
    - [ ] Requestify biedt hem niet aan ("Je hebt de nieuwste versie" na *Zoeken naar updates*).
    - [ ] Met `$env:REQUESTIFY_UPDATE_PRERELEASES = "1"` en Requestify vanuit die PowerShell gestart, wordt hij wél
      aangeboden. (Sluit die Requestify daarna af.)

## F. Verwijderen en opnieuw installeren

17. Verwijder Requestify via *Instellingen* → *Apps*.
    - [ ] In *Taakbeheer* → *Opstart-apps* staat Requestify niet meer.
    - [ ] `%APPDATA%\Requestify` bestaat nog (met je koppelingen).
18. Installeer de nieuwste release opnieuw.
    - [ ] Alle koppelingen en instellingen zijn terug, geen wizard, en starten met Windows staat weer zoals voorheen.
