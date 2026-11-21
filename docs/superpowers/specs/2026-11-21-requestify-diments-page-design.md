# A Requestify page on diments.nl

- **Date:** 2026-11-21
- **Status:** approved by the user ("Goedgekeurd", 2026-11-21)
- **Where it is built:** the Framer project **DIMENTS** (`uY8KdlmtEL7tB6YWT37U`, workspace "Terlouw Media"), not this repository. This repository only supplies facts, copy and screenshots.
- **Approved mock-up:** `.superpowers/brainstorm/1687-1795218521/content/opzet.html` (local, git-ignored), served during the brainstorm. It is the visual and behavioural reference for everything below. `richting.html` in the same folder holds the three directions the user chose from.

## Intent

The user asked for "een leuke interactieve en vrolijke pagina voor Requestify" on their Framer site DIMENTS: "Maak de pagina echt een opzichzelfstaand dingetje, gezien het een app is.. maar vergeet aan de andere kant ook niet de vibe van de website."

- **Who and why:** "Vooral streamers die het nog niet kennen, daarna downloaden." A visitor should first get what Requestify does, ideally by trying it, then download it.
- **The bridge between "its own thing" and the DIMENTS vibe** comes from the app itself. The user: "je kunt in requestify een eigen theme colour kiezen of hij gaat mee met album covers van de nummers." So the page does what the app does: it opens in DIMENTS style, and the app part takes the colours of the album cover that is playing (or a colour the visitor picks).
- **The interaction (user's pick "A: zelf aanvragen"):** the visitor requests a song themself in a fake Twitch chat. Requestify answers in chat, the song lands in the queue, starts playing, confetti pops and the page recolours to the cover.
- **The layout (user's pick: direction 3, "Van DIMENTS naar Requestify"):** a light DIMENTS opening, one of the site's mountain silhouettes leads into a dark "app world" that follows the cover colours, a second silhouette leads back to light DIMENTS for downloading and questions, then the site's footer.
- **Feedback that shaped it:** "De pagina layout/setup is heel basic.. het mag uitgebreider" (now nine sections, approved with "Klopt wel!"); "Maak geen eigen bergen aub" (only the site's own mountain images, see Constraints); "iets minder met de hand written font"; "Polish het, maak het echt compleet met de rest van de site"; "Ook goed en mooie responsive".

Success: on desktop, tablet and phone a new visitor tries a request within seconds, understands the four ways requests come in and that they stay in control, and finds the download with clear install steps. The page sits among `/apps/castirl` and `/apps/signalirl` as a sibling, not as a different site.

## Goals

1. A new page **`/apps/requestify`** in the DIMENTS Framer project, on the shared layout template **Main** (floating nav bar, footer, noise overlay), with the nine sections below.
2. The interactive demo and the recolouring app world, working on desktop, tablet and phone, in Framer's preview and on the published site (see Verification).
3. A download button that always points at the newest Windows installer on GitHub, with the version shown next to it.
4. All work on a **separate Framer branch**, so nothing changes on the live site until the user merges and publishes. *(Builder's call.)* The builder creates that branch through the Framer API (`createBranch`, titled "Requestify-pagina") and switches to it before the first change. Today the project has only `main`. If a branch can't be created, the builder stops and asks; it never builds on `main`.

## Non-goals

- No publishing, no merging into `main`, no deploy. The user does that.
- No change to the floating nav bar or its "Extra's" menu: CastIRL and SignalIRL are not in it either (the menu holds Routes, Minigame, IRL Guide, Discord, Contact). The user can add Requestify there later.
- No changes to other pages. *(Builder's call.)* The hand-over mentions where a link to the new page could go, for example next to CastIRL and SignalIRL wherever those are linked.
- No English version (the site is Dutch).
- No real Twitch, Spotify or Requestify connection: the demo is fully simulated in the browser.
- No changes to Requestify's code, README or releases.
- No analytics, A/B tests or forms.

## Anti-goals (it would fail even if it "works")

- It feels like leaving diments.nl (its own fonts, its own nav, no footer).
- New mountain shapes or other decoration the site doesn't already have.
- Handwriting all over the place.
- A demo that feels fake or breaks: dead buttons, stuck states, text that the real app would never send.
- A heavy page: timers or animations running while off-screen, jank on a phone.
- Claims that aren't true (Mac support, free Spotify, "signed").

## Constraints

### The Framer project, as it is today

- **Layout template "Main"** (`lUEIHAgI_`): Floating Nav Bar (component `h6cTZ9Ozm`, position fixed), a placeholder for the page, Footer (component `mq8igESvt`), a "Noise" image overlay. Every app page uses it. Breakpoints on every page: **Desktop 1440, Tablet 810, Phone 390**.
- **App page conventions** (`/apps/signalirl`, page `HYhm43x6C`; `/apps/castirl`, page `veQAsGfmH`): a "Smooth Scroll" component instance, a "Cursor" instance (code component `CursorFollower`), a full-page "Brand Logo_Alt" frame (absolute, the beige swoosh image) behind the content, a "Page Wrapper" with 12px padding and a "Content Wrapper".
- **Text styles:** H1 Figtree 700 61, H2 Figtree 600 49, H3 600 39, H4 500 31, H5 500 25, H6 500 20, Body, Small 13. App-page titles mix a Figtree 700 run with a **Playfair Display italic 700** run ("CastIRL / *fast, free, reliable*") and appear with a line-by-line text effect on mount (fade and 10px rise, spring 0.6s). Reuse these styles and that effect.
- **Colour tokens:** Text - Dark `rgb(25,42,61)`, Text - Light `rgb(244,244,244)`, DIMENTS Green `rgb(69,108,95)`, DIMENTS Dark Green `rgb(15,68,52)`, DIMENTS Terracotta `rgb(217,167,98)`, Sand Beige, Text - Accent. The page background is `rgb(244,244,244)`. Use tokens, not raw values, in the native sections.
- **Fonts in the project:** Figtree, Playfair Display, Gveret Levin (the handwriting used for "Probeer het!" on SignalIRL), among others.
- **Existing code components** (StreamUI, BusGame, BadgeBook, …) show that code components are normal on this site.

### The site's own imagery (the only decoration allowed)

- Mountain silhouettes (transparent PNGs, 4277×433): MountainMain2 `https://framerusercontent.com/images/IAqSd6rSbuBSOK3aUwXPHrzL4.png`, MountainAlt2 `…/57FwbREIWsNKGF4M7qgjpVj0M.png`, MountainBeige `…/JmESPEcvzBsesWynSgFUJ2I4MY.png`. They may be **tinted** by using them as a CSS mask over a coloured box. That keeps the shape the site's own (the user's "geen eigen bergen"). framerusercontent.com sends `Access-Control-Allow-Origin: *`, which masks need.
- The swoosh: Brand_Logo_Alt `https://framerusercontent.com/images/wb82v0Ay1sSOqv7tdxdVszgB4.png`.
- The Requestify icon (green with the swoosh and beige music notes) and the README screenshots are in the public GitHub repo `DIMENTS/Requestify` on `main`: `docs/superpowers/specs/assets/plan-4-icon/requestify-icon-1024.webp` and `docs/images/nl/{home,wizard,wizard-celebration,connections,log}.png`. They can be uploaded into Framer from their raw.githubusercontent.com URLs. The viewers and covers in them are made up, which the page says.

### Code components in Framer

- One self-contained file per component with one default export; imports only from `react`, `react-dom`, `framer`, `framer-motion`; inline styles; an explicit props interface with the Framer `style` prop on the root; `@framerSupportedLayoutWidth` and `@framerSupportedLayoutHeight` annotations; no `position: fixed` on the root.
- A stable static render on the canvas: no timers, a sensible frozen state.
- Every timer, animation frame, observer and listener cleaned up.
- Editable content through property controls, with short Dutch titles.

### Facts the copy must stay true to

- Windows 10 and 11, 64-bit. Not on Mac.
- Spotify **Premium** is required (Spotify only lets Premium accounts add to the queue).
- Free and open source (MIT). Not code-signed yet: on Windows 11, Smart App Control set to **Aan** blocks it, and SmartScreen shows "Windows heeft uw pc beschermd" once ("Meer info" → "Toch uitvoeren").
- Request sources: chat `!sr`, Channel Points (Requestify can create the reward and refunds points when a request fails), Bits (a cheer with a Spotify link, with a minimum), donations through StreamElements **or** DonoLink. Paid requests can go first and skip the limits.
- Rules, Blacklist (songs, artists, viewers), a 30-day Log, opening when the stream starts and closing when it ends, starting with Windows and waiting in the tray, updates never during a stream.
- The colour setting: "Albumhoes" or "Eigen kleur" with 8 presets (Requestify violet, blue, teal, green, yellow, deep orange, red, pink).
- Chat replies must use the app's own Dutch strings (`src/commonMain/composeResources/values-nl/strings.xml`), for example `Toegevoegd: %1$s – %2$s op #%3$d (≈ %4$d min)`, `Toegevoegd: %1$s – %2$s op #%3$d (zo meteen)`, `Dat nummer kon ik niet vinden.`, `Dat nummer staat al in de wachtrij of is net gespeeld.`, `Verzoekjes zijn nu gesloten.`, and for the Log `In wachtrij, plek %1$d`, `Expliciete nummers zijn geblokkeerd`, `Al %1$d verzoeken in de wachtrij`, `Staat al in de wachtrij of is net gespeeld`, `Langer dan %1$s`.

## Approaches considered

| | Approach | Trade-off |
|---|---|---|
| **A (picked)** | In Framer: the DIMENTS parts as native layers, the dark app world as **one code component** | Native parts stay editable on the canvas with the site's styles. The whole dark middle can recolour together. Its texts are edited through component properties instead of on the canvas. |
| B | Everything native, only the demo as a code component | All editable, but only the demo box could recolour, which loses direction 3's idea. |
| C | A polished HTML mock-up only | Fast and changes nothing, but the user would rebuild it in Framer by hand. |

The user picked **A** ("A: in Framer").

## The page

`/apps/requestify`, page name "Apps / Requestify", layout template **Main**. SEO title **"DIMENTS | Requestify"** plus a short subtitle in the style of the SignalIRL title. A Dutch description of about 150 characters. The social image is the Requestify home screenshot.

Page structure, following the app-page conventions: Smooth Scroll instance → Page Wrapper (holds the absolute Brand Logo_Alt swoosh behind everything) → sections 1, the app-world component, 7, 8 → Cursor instance. The template supplies the nav and the footer (section 9). Sections are full width with content capped at about 1120px. The app world is full-bleed: it must not sit inside horizontal padding.

### 1 · Opening (native, light)

- Left column: the app icon (rounded square) with "Requestify" and "Een app van DIMENTS · voor Windows". Then the title **"Laat je chat de *DJ* zijn."**, with "DJ" as the Playfair italic run in DIMENTS Green and the title effect on mount. *(Builder's call.)* The title uses the site's H1 style (Figtree 700, 61px, like the CastIRL title) rather than the mock-up's heavier 800 at up to 78px, so the page matches its siblings. Then the lead text, from the mock-up.
- *(Changed from the mock-up, following "iets minder met de hand written font".)* The handwritten "probeer hem hieronder ↓" under the visual is dropped. Handwriting appears in exactly two places: "Probeer het!" by the demo and "jouw nummer!" on the Nu speelt card.
- Buttons: **Download voor Windows** (terracotta, scrolls to section 7) and **Eerst proberen ↓** (outline, scrolls to the demo).
- A meta row: ✓ Gratis · ✓ Open source · ✓ the current version.
- Right column: the home screenshot in a slightly rotated window with a soft shadow; it straightens on hover. Around it, gently bobbing labels: Twitch, Spotify, StreamElements, DonoLink, and one chat bubble "PixelPanda !sr levitating". Use Phosphor brand icons where the project's icon set has them (TwitchLogo, SpotifyLogo); otherwise a coloured initial.
- Tablet: two columns become one, the visual goes under the text. Phone: the floating labels hide or shrink so nothing overflows.

### 2–6 · The app world (one code component, `RequestifyWorld`)

One full-width code component with its own top and bottom mountain edges:

- **Top edge:** MountainMain2 as a mask, filled with the world's current background colour, so the dark world rises out of the light opening as mountains. The part above the ridge is transparent, so the page and the swoosh show through.
- **Bottom edge:** MountainBeige as a mask, filled with the page background `rgb(244,244,244)` (a property), so the light download section rises back out of the dark world.
- **Background:** the current theme colour plus two or three soft glows in theme colours, all fading smoothly (about 1.1s) when the theme changes.

**Theme.** Two modes, as in the app: **Albumhoes** (default) derives the theme from the playing song's colour; **Eigen kleur** uses one of the 8 presets. A theme is a dark background, two surfaces, a light "primary" with its "on-primary", two glow colours, text and muted text. The mock-up derives them from one hue (`hsl(h 42% 8%)` background, `hsl(h 88% 81%)` primary, and so on). The builder may refine this, but the text must stay readable (WCAG AA) for every song and preset.

**2 · Probeer het zelf (the demo).** Title "Vraag maar *iets* aan." and a lead. Two panels side by side (stacked on tablet and phone):

- **Chat panel:** a header with a red LIVE badge (OFFLINE when closed), "diments" and a viewer count. Messages scroll inside the panel and auto-follow. Under them, an input with a fixed `!sr` prefix, a send button and 4 suggestion chips (cover dot + title) for songs that aren't queued or playing. A small note under the panel: "In deze demo gaat jouw nummer meteen voor. In het echt sluit je netjes aan."
- **Requestify panel:** a header with the glyph, "Requestify" and an "✓ Verzoeken open" / "✕ Verzoeken dicht" pill. Tabs "Wachtrij · n / Gespeeld / Geweigerd" (only the first is live). Up to 5 queue rows, each with a cover, title/artist, a source chip with the viewer's name, ⚡ for paid requests and the duration. A "Daarna in Spotify" divider with one dimmed row. A **Nu speelt** card: big cover, title, artist, "Aangevraagd door …" or "Je eigen muziek", a wavy progress line and times, and controls. Only ⏭ (skip) works.
- **Flow when the visitor sends a request:**
  1. Their message appears.
  2. After about 0.7s Requestify replies with the app's real strings: no match → "Dat nummer kon ik niet vinden."; already queued or playing → the duplicate line; requests closed → "Verzoekjes zijn nu gesloten."; otherwise "Toegevoegd: … op #1 (zo meteen)". The row springs into the queue at #1 with a highlight.
  3. The current song fast-forwards (about 1.6s).
  4. The visitor's song plays: the world recolours, confetti bursts from the Nu speelt card, a small handwritten "jouw nummer!" appears, and Requestify posts "Nu aan: … (aangevraagd door jij)".
- **Ambient life:** every 4–8s a fake viewer chats, or (about 30% of the time, while open and the queue has room) requests a song, which gets the "≈ n min" reply and joins at the end. Songs play through in demo time (about 22s each), so the colours keep changing even without the visitor. When the queue is empty, "Electric Feel – MGMT" plays as "Je eigen muziek".
- **Song matching:** case-insensitive on title and artist; a link-looking input that matches nothing gets the "kon ik niet vinden" reply.
- **Colour bar** under the demo: "Requestify kleurt mee met de albumhoes. Of kies je eigen kleur:", an Albumhoes / Eigen kleur switch and the 8 swatches. A picked swatch morphs from a circle into a rounded square, like in the app.
- **One handwritten "Probeer het!"** with an arrow, next to the demo on wide screens, hidden on narrow ones.

**3 · Verzoeken van overal.** Title "Verzoeken van *overal*." and a lead. Four cards (4 columns, then 2, then 1): !sr in de chat, Kanaalpunten, Bits, Donaties. Each has an icon, a title, one or two sentences (from the mock-up) and an example chip in the app's chip style. The note "⚡ Betaalde verzoeken kunnen voorgaan en slaan de limieten over." Cards lift slightly on hover.

**4 · Jij houdt de regie.** Title and lead. Left: a "Regels" panel with switches: Per kijker tegelijk: 2, Geen dubbele nummers, Betaald gaat voor (under "Wachtrij"), Expliciete nummers weigeren, Maximale lengte: 10 min (under "Nummers"). Right: a live **Logboek** ("30 dagen bewaard"), where every ~2.6s a sample request is judged against the switches and shown as ✓ "In wachtrij, plek n" (or "In wachtrij, als volgende · betaald gaat voor") or ✕ with the app's reason. Keep the last 5. Below: a Blacklist strip (nummers, artiesten, kijkers, "Met één klik vanuit de wachtrij.").

**5 · Gaat vanzelf open en dicht.** Title and lead (starts with Windows, waits in the tray). A three-step timeline: "19:58 · Je gaat live · Verzoeken open" → "tijdens je stream · Requestify regelt het · Wachtrij loopt" → "23:41 · Stream voorbij · Verzoeken dicht". A big **live switch** ("Je bent live" / "Stream voorbij") that highlights the matching steps and also opens or closes the demo (LIVE badge, pill, replies). The line "Updates komen vanzelf binnen, maar nooit tijdens een stream."

**6 · Zo ziet het eruit.** Title "Zo ziet het *eruit*." A horizontal, snapping row of the four screenshots (wizard, wizard celebration, connections, log) in slightly alternating rotations that straighten on hover. **Captions in Figtree, not handwriting.** Then the note "De kijkers en hoesjes in de screenshots zijn verzonnen."

**Properties to expose:** the section titles and leads (each title as plain text plus the italic word), the songs (an array: title, artist, duration in seconds, two cover colours), the screenshots (an array: image, caption), the page background colour for the bottom edge, and the demo speed (seconds per song). Defaults are the mock-up's content.

### 7 · Downloaden (native, light), anchor `download`

- Title "En het beste van alles, *het is gratis!*" (Playfair run in DIMENTS Green; this echoes SignalIRL's "En het beste van alles, het is gratis!"). Lead: "Gratis en open source. Geen abonnement, geen account bij ons, geen gedoe."
- A big terracotta button **Download Requestify** with a version line under it ("v0.3.1 · Windows 10 en 11 · 64-bit") and the link "of bekijk de code op GitHub →" (`https://github.com/DIMENTS/Requestify`).
- **Always the newest installer:** a code override on the native button (and one on the version text) reads `https://api.github.com/repos/DIMENTS/Requestify/releases/latest` in the browser, then points the button at the asset whose name ends in `.exe` and fills in the version. Without an answer (offline, rate limit, error) the button keeps its canvas link, `https://github.com/DIMENTS/Requestify/releases/latest`, and the text keeps its canvas value. The swoosh mirrored on the right.
- Three white step cards: **1 Check Slimme app-beheer** (Windows 11 only; Aan blocks it, Uit or Evaluatie is fine), **2 Open de setup** (with a small blue SmartScreen mock where "Toch uitvoeren" gently pulses), **3 Koppelen en klaar** (the welcome wizard, one card at a time, a party for every connection). Three columns, then one.
- Requirement chips: Windows 10 of 11 · Spotify Premium · Een Twitch-account.

### 8 · Vragen (native, light)

Title "Nog *vragen*?" and a list of questions that open and close (a small canvas component "FAQ-item" with Dicht and Open variants that switch on tap, with a spring). The six questions and answers come from the mock-up: Premium, Kost het iets, Mac, Waarom waarschuwt Windows, Updates tijdens mijn stream, Wat als iemand iets raars aanvraagt.

### 9 · Footer

From the template, unchanged. The page must leave room at the bottom for the fixed nav bar, so the last section's content isn't hidden behind it.

## Motion and accessibility

- Springy, short motion as in the app (press squeeze on buttons, rows springing in, swatches morphing). With `prefers-reduced-motion`: no confetti, no bobbing, no wave movement; colours still crossfade.
- The app world pauses its timers and animation when it is off-screen or the tab is hidden, and resumes where it was.
- Scrolling inside the chat and the screenshot row must work under the Smooth Scroll component (for example by marking those containers so smooth scroll ignores them). Wheel and touch scroll of the page must not get trapped.
- Inputs and switches have labels. Switches expose their on/off state. Requestify's replies to the visitor are announced politely to screen readers; ambient chatter is not.
- Text contrast at least WCAG AA in every theme.

## Verification

- Framer's canvas shows code components as a frozen picture. Several things only really run on a published site: server rendering and hydration of the app world, the GitHub download override, and Smooth Scroll together with the inner scroll areas.
- **The builder may publish the branch to test it** (user: "Ja, preview mag"). A branch publish updates only the branch's own preview address. Publishing `main`, deploying to production and merging stay the user's.
- On that preview, the builder checks desktop, tablet and phone widths in a real browser: the demo flow, recolouring, the GitHub download link and version, scrolling inside the chat and the screenshot row, and reduced motion.
- Before that, the builder checks behaviour in a local browser test (see below) and every breakpoint with canvas screenshots.

## Decisions left to the builder

- How exactly themes are derived from a hue or cover colours, within the readability rule.
- How the component adapts to its width (wide, medium, narrow), as long as it looks right at 1440, 810 and 390 and everything in between.
- Icons per source card. *(Changed from the mock-up's emoji.)* Use Phosphor-style line icons drawn as inline SVG in the component (chat bubble, Twitch, diamond, coins), in the theme's primary colour.
- Exact copy polish within the meaning of the mock-up and the facts above.
- How to verify behaviour in a real browser before handing over (Framer's canvas renders code components statically), for example a local test page with a small stand-in for the `framer` module.
- Folder placement of the new code files, following the project's structure.

## Addendum 2026-11-22 — split the app world, and a real carousel

The user, after seeing the first build: "Ik zie dat die app world een groot onderdeel is van de pagina en dit kan best zwaar zijn voor sommige browsers. Is het niet mogelijk om het op te splitten en elementen..?" and "voor die carousel mag de scrollbar weg en mag het op een andere manier duidelijk dat je het heen en weer kunt vegen/klikken/draggen." Approved: "Ja, zo opsplitsen". This replaces the single `RequestifyWorld` component in sections 2–6.

**Why it was heavy:** one React component held all five sections; the demo's 250 ms clock re-rendered all of them, and every colour change animated the whole subtree.

**The split:**
- **Native Framer layers** (static HTML, editable on the canvas): every section title and intro, the four "Verzoeken van overal" cards, the blacklist strip, the short notes, and the screenshots.
- **Four small code components**, each re-rendering only itself and only while on screen:
  1. **Demo:** chat, queue, now playing and the colour bar. The only one with the clock.
  2. **Regels:** the switches and the live log.
  3. **Live:** the open/closed timeline and its switch; it still opens and closes the demo.
  4. **Achtergrond:** the dark colour, glows and both mountain edges, behind the native layers; it follows the colour the demo picks.
- The components share two values, the hue and live/closed, through a tiny store (`Requestify/RequestifyShared.tsx`, which also holds the tested helpers).
- **Trade-off (accepted):** the background, mountains, glows and the three interactive parts keep following the album cover. The native layers use a fixed light text colour and translucent cards, so the italic accent words in the dark sections no longer change with the cover; they use DIMENTS Terracotta.

**The carousel:** the site's own Carousel component (as on CastIRL), with no scrollbar, arrows to click, dots that show the position, snapping, a peek of the next screenshot at the edge, and swipe/drag. Captions in Figtree.
