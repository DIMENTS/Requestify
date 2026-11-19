# Plan 5 — handmatige checks (uitbrengen aan vrienden)

Deze checks gaan over wat alleen een geïnstalleerde versie kan laten zien: installeren, de welkomstwizard, bijwerken via
GitHub, verwijderen en (sinds plan 6) het bot-account. Doe ze in deze volgorde. Kloppen een stap niet, noteer dan wat
je zag en ga door met de rest.

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
   - [ ] De naam is `Requestify-0.0.<getal>.exe`; een tweede build, ook binnen dezelfde minuut, heeft een hoger getal.
4. **Installeren:** dubbelklik het bestand.
   - [ ] Geen beheerdersvraag (UAC) en geen keuzes.
   - [ ] Requestify start na de installatie vanzelf.
   - [ ] Geïnstalleerd in `%LOCALAPPDATA%\Requestify`; Start-menu en bureaublad hebben een snelkoppeling.
   - [ ] Je bestaande koppelingen zijn er nog (je data in `%APPDATA%\Requestify` bleef staan) en je krijgt **geen**
     welkomstwizard, want je had Requestify al ingesteld.
   - [ ] Ook als er tegelijk iemand anders op de pc is aangemeld (*Gebruiker wisselen*): de installatie lukt en komt
     alleen bij jou terecht.
   - [ ] Installeer een tweede lokale build vlak nadat je bij Windows bent aangemeld (Requestify start dan net met
     Windows mee): de installatie lukt en Requestify draait daarna één keer, niet twee keer.
5. **Over Requestify:** *Instellingen* → onderaan.
   - [ ] Versie `0.0.<getal>`, en geen "Zoeken naar updates" (een lokale build werkt zichzelf nooit bij).

## C. De welkomstwizard van nul af

6. Sluit Requestify af (systeemvak → *Afsluiten*). Hernoem `%APPDATA%\Requestify` naar `Requestify-bewaard` en start
   Requestify opnieuw.
   - [ ] De welkomstwizard verschijnt: "Hé! Welkom bij Requestify".
   - [ ] De titelbalk zegt "Nog niets gekoppeld" (niet "Alles verbonden").
   - [ ] Spotify koppelen werkt vanuit de wizard; na het inloggen: vinkje, confetti, het Spotify-logo vliegt naar boven.
   - [ ] Twitch koppelen: na het feestje blijft de Twitch-kaart staan, met "Optioneel: laat je bot de antwoorden
     sturen." en *Verder*.
   - [ ] Bij Donaties gaat "Geen" meteen door naar de volgende kaart; een sleutel plakken viert pas als de test lukt.
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

## E. Bijwerken van 0.2.0 naar 0.3.0

11. Zet tag `v0.3.0` (plan 6: het bot-account) en publiceer die release, met wat er nieuw is.
12. Start Requestify (0.2.0) en wacht ruim 2 minuten.
    - [ ] *Over Requestify*: "Versie 0.3.0 staat klaar", en een stipje bij *Instellingen*. Geen melding, geen balk.
13. **Niet tijdens een stream:** ga live (of open de verzoeken).
    - [ ] "Nu bijwerken" is uitgeschakeld, met de uitleg eronder.
14. **Herstart midden in een stream:** sluit Requestify af terwijl je live bent en start hem binnen 10 minuten opnieuw.
    - [ ] Hij installeert de update níet bij die start (Requestify blijft 0.2.0).
15. Ga offline, sluit de verzoeken en klik op **Nu bijwerken** (dubbelklikken mag: het telt als één poging).
    - [ ] Requestify sluit, installeert zonder vragen en start vanzelf weer op.
    - [ ] Het kaartje "Bijgewerkt naar 0.3.0" staat op Home met de tekst van de release; na sluiten komt het niet terug.
    - [ ] Het Logboek heeft "Bijgewerkt van 0.2.0 naar 0.3.0".
    - [ ] Starten met Windows staat nog aan (een update laat dat met rust).
    - [ ] *Over Requestify* heeft een knop *Wat is er nieuw* die de releasepagina van 0.3.0 opent.
16. **Een mislukte installatie** (de oude versie moet blijven): sluit Requestify af via het systeemvak (*Afsluiten*) en
    installeer de nieuwste MSI met een installatiemap die niet kan bestaan:
    `msiexec /i "<pad>\Requestify-0.3.0.msi" /qb INSTALLDIR="Q:\Bestaat-niet\\"` (kies een schijfletter die je niet hebt).
    - [ ] De installatie mislukt met een foutmelding.
    - [ ] `%LOCALAPPDATA%\Requestify\Requestify.exe` bestaat nog en Requestify start gewoon (de oude versie is terug).
17. **Pre-release, en de update-hulp van 0.3.0:** zet tag `v0.3.1` op dezelfde commit als `v0.3.0` en publiceer hem als
    *pre-release*. (Stap 15 gebruikte nog de update-hulp van 0.2.0; dit is de eerste keer dat die van 0.3.0 draait.)
    - [ ] Requestify (0.3.0) biedt hem niet aan ("Je hebt de nieuwste versie" na *Zoeken naar updates*).
    - [ ] Sluit Requestify af, zet `$env:REQUESTIFY_UPDATE_PRERELEASES = "1"` en start
      `& "$env:LOCALAPPDATA\Requestify\Requestify.exe"` vanuit die PowerShell: na ruim 2 minuten staat 0.3.1 klaar.
    - [ ] Ga offline, sluit de verzoeken en klik op **Nu bijwerken**: Requestify sluit, installeert zonder vragen en
      komt vanzelf terug als 0.3.1 (kaartje "Bijgewerkt naar 0.3.1", en het Logboek zegt "Bijgewerkt van 0.3.0 naar
      0.3.1").
    - [ ] Kijk in *Windows-beveiliging* → *Beveiligingsgeschiedenis*: Defender heeft de update-helper niet tegengehouden
      (vanaf 0.3.0 een leesbaar PowerShell-commando, geen versleuteld).
    - [ ] Opruimen: verwijder de pre-release `v0.3.1` op GitHub. Je blijft zelf op 0.3.1 (dezelfde code); de volgende
      echte release krijgt een hoger nummer dan 0.3.1 (bijvoorbeeld `v0.3.2`).

## F. Verwijderen en opnieuw installeren

18. Verwijder Requestify via *Instellingen* → *Apps*, **terwijl Requestify draait**.
    - [ ] Het verwijderen vraagt Requestify te sluiten, of sluit hem zelf; het lukt.
    - [ ] In *Taakbeheer* → *Opstart-apps* staat Requestify niet meer.
    - [ ] `%APPDATA%\Requestify` bestaat nog (met je koppelingen).
    - [ ] `%LOCALAPPDATA%\Requestify-updates` (de gedownloade updates) is weg.
19. Installeer de nieuwste release opnieuw.
    - [ ] Alle koppelingen en instellingen zijn terug, geen wizard, en starten met Windows staat weer zoals voorheen.

## G. Bot-account

Je hebt een tweede Twitch-account nodig voor de bot (bijvoorbeeld `jouwnaambot`).

20. *Koppelingen* → Twitch → *Bot-account (optioneel)* → **Bot koppelen**. Klik *Link kopiëren*, open een
    **privévenster**, plak de link en log daar in met het bot-account.
    - [ ] Het blokje zegt "Antwoorden komen van @…" met de naam van je bot.
21. **Je eigen account per ongeluk:** *Ontkoppelen*, dan opnieuw *Bot koppelen*, maar open de link in je gewone browser
    (waar je als jezelf bent ingelogd).
    - [ ] Het blokje zegt "Dat is je eigen account. Log in met je bot, bijvoorbeeld in een privévenster."
    - [ ] Je eigen Twitch-koppeling werkt gewoon door (chat, Kanaalpunten).
    - [ ] Koppel de bot daarna weer goed (stap 20).
22. Typ `!song` in je chat.
    - [ ] Het antwoord komt van de bot, niet van jou.
23. **De bot valt weg:** ga op twitch.tv als de bot naar *Instellingen* → *Connecties* en verbreek Requestify. Typ
    daarna `!song`.
    - [ ] Het antwoord komt nu van jou.
    - [ ] Eén melding "De bot moet opnieuw inloggen"; de titelbalk zegt "Twitch-bot: log opnieuw in".
    - [ ] Het Logboek heeft een regel bij *Twitch-bot*.
    - [ ] *Opnieuw inloggen* in het bot-blokje (privévenster) en weer `!song`: het antwoord komt weer van de bot, zonder
      Requestify te herstarten.
24. **Moderator:** typ in je chat `/mod <botnaam>` (de knop *Kopiëren* naast de tip zet het klaar) en klik
    *Opnieuw kijken*.
    - [ ] Het blokje zegt "Moderator ✓".
25. *Ontkoppelen*.
    - [ ] Antwoorden komen weer van jou, zonder waarschuwing.
26. Heb je ooit een StreamElements-account bij de hand: kijk of de valuta bij *Donaties* klopt (de app las hem zonder
    live voorbeeld).
