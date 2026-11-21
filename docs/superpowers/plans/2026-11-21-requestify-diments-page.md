# Requestify page on diments.nl — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `/apps/requestify` in the DIMENTS Framer project on its own branch: native DIMENTS sections (opening, download, questions) around one recolouring, interactive code component (the app world), plus overrides that keep the download on the newest release.

**Architecture:** Three Framer code files under a `Requestify/` folder. `RequestifyWorld.tsx` holds pure, exported helpers (matching, theming, rules, copy) and the default-exported world component. `RequestifyRelease.tsx` holds the GitHub release helper and two overrides. `FAQ-item` is a canvas component. The code is written and tested locally first: Node's test runner for the helpers, and a browser harness for the component with a small stand-in for the `framer` module. Then it is pushed to Framer, where programs run through the Framer MCP `exec` tool (session from `open_project`).

**Tech Stack:** Framer (MCP tools `open_project`, `exec`, `read_docs`), React 18 code components (TypeScript, inline styles), `esbuild` + `node --test` (Node 24) for local tests, the built-in browser pane for harness and preview checks.

**Spec:** `docs/superpowers/specs/2026-11-21-requestify-diments-page-design.md`. The approved mock-up `.superpowers/brainstorm/1687-1795218521/content/opzet.html` is the reference for copy, layout and motion.

## Global Constraints

- Framer project `uY8KdlmtEL7tB6YWT37U`. All edits on branch **"Requestify-pagina"**, never on `main`. Publishing is allowed only on that branch (preview address). Never merge, publish `main` or deploy.
- Page path `/apps/requestify`, layout template **Main** (`lUEIHAgI_`), breakpoints Desktop 1440 / Tablet 810 / Phone 390.
- Only the site's own imagery as decoration: MountainMain2 `https://framerusercontent.com/images/IAqSd6rSbuBSOK3aUwXPHrzL4.png`, MountainBeige `https://framerusercontent.com/images/JmESPEcvzBsesWynSgFUJ2I4MY.png` (as tinted CSS masks), Brand_Logo_Alt `https://framerusercontent.com/images/wb82v0Ay1sSOqv7tdxdVszgB4.png`.
- Fonts: Figtree (body, headings), Playfair Display italic 700 (accent words), Gveret Levin only for "Probeer het!" and "jouw nummer!".
- Colour tokens in native layers: Text - Dark `7db08100-a51e-46c5-b9a4-d9c0c786e282`, Text - Light `19372cbd-9f9b-4d10-827b-3f36ef158f18`, DIMENTS Green `23f1fc71-8e49-4a87-af1c-373bb84d6af5`, DIMENTS Dark Green `00da93e0-f726-490f-a85a-19e1135338ae`, DIMENTS Terracotta `0c01ec93-b531-4ff6-96ce-d35f99132d37`, Sand Beige `48e2dc8c-4c06-4deb-ad5c-47a6a1c34c49`. Page background `rgb(244,244,244)`.
- Text styles: H1 `mgCMDOmwo`, H2 `y2FKjVhjp`, H3 `zVXugpBVK`, H4 `nKkATFwcE`, H5 `UMEUZQXip`, H6 `mQJHUtEfv`, Body `bhlLh2Ykm`, Small `jGx6HfmEp`. App-page titles: a Figtree 700 run plus a Playfair Display italic 700 run, line-by-line text effect on mount (opacity 0, y 10px, spring 0.6s, bounce 0, delay 0.3s, stagger 0.07s), as on CastIRL title `Fk0rWhnDG`.
- Code files: imports only from `react`, `react-dom`, `framer`, `framer-motion`. Inline styles, except one scoped `<style>` element per component for keyframes and the Gveret Levin `@import`. `@framerSupportedLayoutWidth any-prefer-fixed` / `@framerSupportedLayoutHeight auto`. No `position: fixed` on the root. Static frozen render when `useIsStaticRenderer()` is true. Every timer, frame, observer and listener cleaned up.
- All copy in Dutch. Chat and Log copy exactly as the app's strings (spec, "Facts the copy must stay true to").
- No git commits in this plan: the work lives in Framer and in the git-ignored `.superpowers/requestify-page/`. The spec and this plan stay uncommitted until the user asks.

## Review Focus

1. **Hostile or odd chat input** (`<img src=x onerror=…>`, 500 characters, only spaces, a Spotify link). It must show as plain text, be capped at 60 characters, and get exactly one sensible reply or none for blank input. Tests: Task 1 `matchSong` cases, Task 2 harness check H6.
2. **Rapid repeated requests** (a suggestion chip clicked 10 times quickly). Expect one queue row and duplicate replies for the rest; the fast-forward fires once. Test: Task 2 harness check H7.
3. **Coming back after a while** (a hidden tab or the world scrolled off-screen for minutes). Expect no burst of messages or song changes: timers pause and resume. Test: Task 2 harness check H9, using the `paused` hook.
4. **GitHub unreachable or rate-limited.** The download button keeps its canvas link (`https://github.com/DIMENTS/Requestify/releases/latest`) and the version text keeps its canvas text. Tests: Task 3 `pickInstaller` cases, and a Task 6 check with the request blocked.
5. **Narrow screens (390px) and Smooth Scroll.** No horizontal page scroll, the chat and the screenshot row scroll inside themselves, and the page wheel/touch scroll never gets trapped. Tests: Task 2 harness check H8, Task 6 preview checks.

---

### Task 1: Local workspace and the world's pure helpers

**Files:**
- Create: `.superpowers/requestify-page/package.json` (private, `"type": "module"`, devDependencies `esbuild`, `react@18`, `react-dom@18`)
- Create: `.superpowers/requestify-page/framer-stub.tsx` (exports `addPropertyControls` no-op, `ControlType` as a string map, `useIsStaticRenderer = () => false`, `RenderTarget = { current: () => "preview", canvas: "canvas", preview: "preview" }`)
- Create: `.superpowers/requestify-page/RequestifyWorld.tsx` (helpers only in this task)
- Create: `.superpowers/requestify-page/world.test.ts`
- Create: `.superpowers/requestify-page/test.mjs` (bundles each `*.test.ts` with esbuild, aliasing `framer` → `./framer-stub.tsx`, platform node, into `out/`, then runs `node --test out/`)

**Interfaces:**
- Produces (named, lowercase exports of `RequestifyWorld.tsx`):
  - `type Song = { title: string; artist: string; seconds: number; color1: string; color2: string }`
  - `type Theme = { bg: string; surf: string; surf2: string; primary: string; onPrimary: string; glow1: string; glow2: string; text: string; muted: string }` (all `#rrggbb`)
  - `normalize(s: string): string`: lowercase, strip everything except `a-z0-9` and spaces, collapse spaces, trim.
  - `matchSong(query: string, songs: readonly Song[]): Song | null`: normalized query (≤ 60 chars) contained in the normalized "title artist", or the normalized title starts with it. Empty → null. Queries containing `http` or `spotify` → null.
  - `hueOf(hex: string): number` (0–359)
  - `themeFromHue(hue: number): Theme`
  - `contrast(a: string, b: string): number` (WCAG ratio)
  - `type Rules = { perViewer: boolean; noDup: boolean; paidFirst: boolean; noExplicit: boolean; maxLen: boolean }`
  - `type Sample = { viewer: string; title: string; seconds: number; explicit?: boolean; dup?: boolean; has?: number; paid?: string }`
  - `judge(s: Sample, r: Rules): string | null`. Order: explicit → length (> 600s) → paid with paidFirst passes → per viewer (`has >= 2`) → duplicate.
  - `waitMinutes(remainingNow: number, aheadSeconds: readonly number[]): number`: `max(1, round((remainingNow + Σ ahead) / 60))`.
  - `formatTime(seconds: number): string`
  - `splitItalic(s: string): { text: string; italic: boolean }[]`: `*…*` marks the italic part.
  - `reply`: `{ added(t, a, pos, min), addedSoon(t, a), notFound, duplicate, closed, nowPlaying(t, a, by) }`
  - `DEFAULT_SONGS: Song[]`: Midnight City/M83/243/#B04BE0/#E9549A, Levitating/Dua Lipa/203/#F2762E/#E33B5B, Mr. Brightside/The Killers/222/#2F7BE8/#3B5BE3, Dreams/Fleetwood Mac/257/#3FAE5A/#1E8F6E, September/Earth, Wind & Fire/215/#F2B21F/#E8702A, Take On Me/a-ha/225/#22B8D6/#2B7BD8, Bohemian Rhapsody/Queen/354/#E5484D/#8E2BB8, Electric Feel/MGMT/229/#1FB5A0/#2B6FD8. `color1` is the album seed.

- [ ] **Step 1: Set up the workspace.** Write `package.json`, `framer-stub.tsx` and `test.mjs`, then run `npm install` in `.superpowers/requestify-page/`. Expected: `added … packages`, no errors.
- [ ] **Step 2: Write the failing tests** in `world.test.ts` (`node:test` + `node:assert/strict`):

```ts
test("matchSong finds by title, artist and loose spelling", () => {
  assert.equal(matchSong("midnight city", DEFAULT_SONGS)?.title, "Midnight City")
  assert.equal(matchSong("MIDNIGHT", DEFAULT_SONGS)?.title, "Midnight City")
  assert.equal(matchSong("m83", DEFAULT_SONGS)?.title, "Midnight City")
  assert.equal(matchSong("  take on me ", DEFAULT_SONGS)?.title, "Take On Me")
  assert.equal(matchSong("mr brightside", DEFAULT_SONGS)?.title, "Mr. Brightside")
})
test("matchSong refuses blank, links, junk and markup", () => {
  for (const q of ["", "   ", "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC", "zzzz", "<img src=x onerror=alert(1)>", "x".repeat(500)])
    assert.equal(matchSong(q, DEFAULT_SONGS), null)
})
test("every theme is readable (WCAG AA)", () => {
  for (let h = 0; h < 360; h += 5) {
    const t = themeFromHue(h)
    assert.ok(contrast(t.text, t.bg) >= 7, `text/bg ${h}`)
    assert.ok(contrast(t.text, t.surf2) >= 4.5, `text/surf2 ${h}`)
    assert.ok(contrast(t.muted, t.bg) >= 4.5, `muted/bg ${h}`)
    assert.ok(contrast(t.primary, t.bg) >= 4.5, `primary/bg ${h}`)
    assert.ok(contrast(t.onPrimary, t.primary) >= 4.5, `on/primary ${h}`)
  }
})
test("hueOf", () => { assert.equal(hueOf("#ff0000"), 0); assert.equal(hueOf("#00ff00"), 120); assert.equal(hueOf("#0000ff"), 240) })
test("judge follows the app's rules and wording", () => {
  const all: Rules = { perViewer: true, noDup: true, paidFirst: true, noExplicit: true, maxLen: true }
  assert.equal(judge({ viewer: "n", title: "x", seconds: 300, explicit: true }, all), "Expliciete nummers zijn geblokkeerd")
  assert.equal(judge({ viewer: "n", title: "x", seconds: 1411 }, all), "Langer dan 10:00")
  assert.equal(judge({ viewer: "n", title: "x", seconds: 200, has: 2 }, all), "Al 2 verzoeken in de wachtrij")
  assert.equal(judge({ viewer: "n", title: "x", seconds: 200, dup: true }, all), "Staat al in de wachtrij of is net gespeeld")
  assert.equal(judge({ viewer: "n", title: "x", seconds: 200, has: 2, paid: "SE €2,50" }, all), null)
  assert.equal(judge({ viewer: "n", title: "x", seconds: 200, has: 2, paid: "SE €2,50" }, { ...all, paidFirst: false }), "Al 2 verzoeken in de wachtrij")
  assert.equal(judge({ viewer: "n", title: "x", seconds: 300, explicit: true }, { ...all, noExplicit: false }), null)
})
test("copy matches the app", () => {
  assert.equal(reply.added("Dreams", "Fleetwood Mac", 3, 11), "Toegevoegd: Dreams – Fleetwood Mac op #3 (≈ 11 min)")
  assert.equal(reply.addedSoon("September", "Earth, Wind & Fire"), "Toegevoegd: September – Earth, Wind & Fire op #1 (zo meteen)")
  assert.equal(reply.notFound, "Dat nummer kon ik niet vinden.")
  assert.equal(reply.duplicate, "Dat nummer staat al in de wachtrij of is net gespeeld.")
  assert.equal(reply.closed, "Verzoekjes zijn nu gesloten.")
  assert.equal(reply.nowPlaying("September", "Earth, Wind & Fire", "jij"), "Nu aan: September – Earth, Wind & Fire (aangevraagd door jij)")
})
test("time helpers", () => {
  assert.deepEqual([0, 203, 354, 1411].map(formatTime), ["0:00", "3:23", "5:54", "23:31"])
  assert.equal(waitMinutes(30, []), 1)
  assert.equal(waitMinutes(120, [203, 222]), 9)
})
test("splitItalic", () => {
  assert.deepEqual(splitItalic("Vraag maar *iets* aan."), [{ text: "Vraag maar ", italic: false }, { text: "iets", italic: true }, { text: " aan.", italic: false }])
  assert.deepEqual(splitItalic("Geen accent"), [{ text: "Geen accent", italic: false }])
})
```

- [ ] **Step 3: Run `node test.mjs`.** Expected: FAIL (exports missing).
- [ ] **Step 4: Implement the helpers in `RequestifyWorld.tsx`.** `themeFromHue` builds colours in HSL and converts them to hex. Start from the mock-up's values (bg `hsl(h 42% 8%)`, surf `13%`, surf2 `19%`, primary `hsl(h 88% 81%)`, onPrimary `hsl(h 50% 14%)`, glow1 `hsl(h 68% 33%)`, glow2 `hsl(h+38 66% 31%)`, text `hsl(h 45% 95%)`, muted `hsl(h 20% 72%)`). Adjust lightness until every hue passes the contrast test.
- [ ] **Step 5: Run `node test.mjs`.** Expected: all tests pass.

### Task 2: The app world component, proven in a local browser harness

**Files:**
- Modify: `.superpowers/requestify-page/RequestifyWorld.tsx` (add the default export and property controls)
- Create: `.superpowers/requestify-page/harness.tsx` (renders `<RequestifyWorld>` with defaults inside a 100%-wide page with the `#F4F4F4` background, plus a light block above and below. The width comes from `?w=1440|810|390`, and `?paused=1` sets the `paused` prop.)
- Create: `.superpowers/requestify-page/harness.html`, plus a `build:harness` step in `test.mjs` (esbuild bundle for the browser, `framer` aliased to the stub, output `out/harness.js`)

**Interfaces:**
- Consumes: Task 1 helpers.
- Produces: `export default function RequestifyWorld(props: WorldProps)`, where
  `WorldProps = { songs: Song[]; shots: { image: string; caption: string }[]; titles: { demo: string; sources: string; rules: string; auto: string; gallery: string }; leads: { demo: string; sources: string; rules: string; auto: string }; pageBackground: string; secondsPerSong: number; paused?: boolean; style?: React.CSSProperties }`.
  Defaults: titles `"Vraag maar *iets* aan."`, `"Verzoeken van *overal*."`, `"Jij houdt de *regie*."`, `"Gaat vanzelf *open en dicht*."`, `"Zo ziet het *eruit*."`; leads from the mock-up; `pageBackground` `#F4F4F4`; `secondsPerSong` 22. The shots default to the four README screenshots via raw.githubusercontent.com URLs (replaced by Framer assets in Task 5), with Figtree captions "Hé! De welkomstwizard", "Een feestje bij elke koppeling", "Elke koppeling stap voor stap", "Het logboek: wat er gebeurde en waarom".
  Test hooks (`data-rq` attributes): `root` (also carries `data-rq-hue` = the hue in use), `chat`, `input`, `send`, `sug` (each chip), `queue-row` (each row, with `data-title`), `now-title`, `pill`, `live-badge`, `swatch` (each), `mode-album`, `mode-custom`, `live-switch`, `rule` (each switch, with `data-rule`), `log-row`, `announce`.
- Behaviour: as in the spec's sections 2–6 and the mock-up. Render chat text with React text nodes, never `innerHTML`. The input has `maxLength` 60. The world pauses (no timers, no frames) while `paused`, the tab is hidden, or the root is off-screen (IntersectionObserver), and resumes in place. Ambient chat is one chained `setTimeout`, not an interval. Inner scrollers (`chat`, gallery) get `data-lenis-prevent`. Confetti is drawn in an absolutely positioned overlay inside the root, and skipped under reduced motion. The two ridges are `div`s with `mask-image` of the mountain PNGs: the top one filled with `theme.bg`, the bottom one with `pageBackground`.
- Responsive: measure the root's own width (ResizeObserver). `wide` ≥ 1000 gives the mock-up's desktop grid. `mid` 640–999 stacks the demo panels, shows 2 source columns, stacks the rules, and makes the timeline vertical. `narrow` < 640 gives one column, hides the "Probeer het!" note, wraps the swatches, and shows the queue without the duration column.

- [ ] **Step 1: Write the harness checks** as a script `checks.js` for the browser pane's `javascript_tool`. Each check returns `{ id, pass, detail }`:
  - H1 Typing `september` and sending shows within 1.2s a bot line ending in `Toegevoegd: September – Earth, Wind & Fire op #1 (zo meteen)`, and the first `queue-row` has `data-title="September"`.
  - H2 Within 4.5s `now-title` is `September`, and `root[data-rq-hue]` equals `hueOf("#F2B21F")`.
  - H3 `zzzz` gives `Dat nummer kon ik niet vinden.`
  - H4 `september` while it plays gives `Dat nummer staat al in de wachtrij of is net gespeeld.`
  - H5 With `live-switch` off: `live-badge` is `OFFLINE`, `pill` is `✕ Verzoeken dicht`, and a request gets `Verzoekjes zijn nu gesloten.`
  - H6 `<img src=x onerror=alert(1)>` adds no `img` element inside `chat`, and the visible text contains `<img`.
  - H7 Clicking one `sug` 10 times within 300ms gives exactly one `queue-row` with that title, and at most one fast-forward (the `now-title` changes once).
  - H8 At `?w=390`, `?w=810` and `?w=1440`, `document.documentElement.scrollWidth <= innerWidth`, and the `chat` element has `scrollHeight > clientHeight` after 12 messages.
  - H9 With `?paused=1`, after 10s the chat message count and `now-title` are unchanged.
  - H10 Clicking `swatch` #3 sets `data-rq-hue` to that preset's hue and keeps it when the song changes. `mode-album` restores the playing song's hue.
  - H11 Turning `rule[data-rule="noExplicit"]` off makes the next "Lose Yourself" `log-row` show ✓.
  - H12 Accessibility basics:
    - `input` has an `aria-label`;
    - every `rule`, `live-switch` and `swatch` exposes `aria-pressed` matching its state;
    - an `aria-live="polite"` element (`data-rq="announce"`) holds the latest reply addressed to `jij`, and does not change on ambient replies.
- [ ] **Step 2: Build the harness with the component stub-only and run the checks.** Expected: all fail.
- [ ] **Step 3: Implement `RequestifyWorld`,** porting sections 2–6 from `opzet.html`. Use the copy changes from the spec: Figtree captions, line icons instead of emoji, handwriting only for "Probeer het!" and "jouw nummer!".
- [ ] **Step 4: Rebuild and run the checks** at each width, and look at a screenshot at 1440, 810 and 390. Expected: H1–H11 pass. The screenshots match the mock-up's look, and nothing overflows or overlaps.
- [ ] **Step 5: Run `node test.mjs`.** Expected: still green.

### Task 3: Release helper and overrides

**Files:**
- Create: `.superpowers/requestify-page/RequestifyRelease.tsx`
- Create: `.superpowers/requestify-page/release.test.ts`

**Interfaces:**
- Produces:
  - `pickInstaller(release: unknown): { url: string; version: string } | null`. Uses the first asset whose `name` ends in `.exe`; `version` is `tag_name` without the leading `v`.
  - `latestRelease(): Promise<{ url: string; version: string } | null>`. Fetches `https://api.github.com/repos/DIMENTS/Requestify/releases/latest` once per page load (a memoised promise), with a 6s timeout, and resolves `null` on any failure.
  - Overrides: `withLatestDownload` (sets `link`/`href` to the `.exe` URL once known, otherwise leaves the canvas link), `withLatestVersion` (text `v{version} · Windows 10 en 11 · 64-bit`), `withLatestVersionShort` (text `v{version}`). Before the answer arrives and on failure, they render the canvas values unchanged.

- [ ] **Step 1: Write the failing tests:**

```ts
const rel = { tag_name: "v0.3.1", assets: [
  { name: "Requestify-0.3.1.msi", browser_download_url: "https://github.com/DIMENTS/Requestify/releases/download/v0.3.1/Requestify-0.3.1.msi" },
  { name: "Requestify-Setup-0.3.1.exe", browser_download_url: "https://github.com/DIMENTS/Requestify/releases/download/v0.3.1/Requestify-Setup-0.3.1.exe" },
  { name: "SHA256SUMS.txt", browser_download_url: "https://github.com/DIMENTS/Requestify/releases/download/v0.3.1/SHA256SUMS.txt" } ] }
test("pickInstaller picks the exe and the version", () => {
  assert.deepEqual(pickInstaller(rel), { url: rel.assets[1].browser_download_url, version: "0.3.1" })
})
test("pickInstaller refuses anything else", () => {
  for (const bad of [null, "x", {}, { tag_name: "v1.0.0", assets: [] }, { assets: rel.assets }, { tag_name: "v1", assets: [{ name: "a.exe" }] }])
    assert.equal(pickInstaller(bad), null)
})
```

- [ ] **Step 2: Run `node test.mjs`.** Expected: FAIL.
- [ ] **Step 3: Implement** the helper, `latestRelease` and the three overrides (the canonical override shape from the Framer code-components skill: `forwardRef`, spread props, merge style).
- [ ] **Step 4: Run `node test.mjs`.** Expected: all green.

### Task 4: Framer branch, page shell and assets

**Files (Framer):** branch "Requestify-pagina"; a new page `/apps/requestify`; code files `Requestify/RequestifyWorld.tsx`, `Requestify/RequestifyRelease.tsx`.

**Interfaces:**
- Consumes: the local sources from Tasks 1–3 (pasted into the program as strings).
- Produces: the page id, its three breakpoint ids, the asset references (icon, five screenshots), the component id of `RequestifyWorld`, and the override ids. Store them with `api.store("rq", {...})` for later programs.

- [ ] **Step 1: Re-open the project if the session expired,** then `tree.createBranch({ title: "Requestify-pagina" })` and `await tree.switchBranch(id)`. Check `tree.getActiveBranch().title === "Requestify-pagina"`. If that fails, stop and tell the user.
- [ ] **Step 2: Make the page shell.** Duplicate the SignalIRL page (`HYhm43x6C`). On the copy:
  - set the path to `/apps/requestify` and the name to "Apps / Requestify";
  - set metadata: title `DIMENTS | Requestify - Songverzoeken voor je stream`, a Dutch description of about 150 characters;
  - remove the Content Wrapper's children on every breakpoint;
  - set Page Wrapper and Content Wrapper horizontal padding to 0;
  - keep Smooth Scroll, Cursor and Brand Logo_Alt.

  Check: three breakpoints, template `lUEIHAgI_`, an empty content wrapper, and a screenshot that shows the nav and the footer.
- [ ] **Step 3: Upload the assets** with `tree.assetReference(url)` from `https://raw.githubusercontent.com/DIMENTS/Requestify/main/`: `docs/superpowers/specs/assets/plan-4-icon/requestify-icon-1024.webp` and `docs/images/nl/{home,wizard,wizard-celebration,connections,log}.png`. Set the page's social image (metadata) to the home screenshot. Check: six asset references are returned and the metadata shows the image.
- [ ] **Step 4: Create both code files** with `api.files.create`, using the exact local sources. Check:
  - `diagnostics` is empty;
  - `components` lists `RequestifyWorld` (component) and the three overrides (override), and no helper;
  - if helpers show up as components, move them into one exported `const rq = {…}` object and re-run the local tests and Task 2 checks before pushing again.
- [ ] **Step 5: Store the ids** with `api.store`.

### Task 5: Native sections and the world on the page

**Files (Framer):** the page's breakpoints, a new canvas component "FAQ-item".

**Interfaces:**
- Consumes: the ids from Task 4.
- Produces: section frames named "Opening", "App World", "Downloaden", "Vragen"; anchors `probeer` (on the world instance) and `download`.

- [ ] **Step 1: Read the guides** before mutating: `references/workflow/part-1.md`, `references/project/layout/part-1.md`, `references/project/replicas/part-1.md`, `references/project/rich-text/part-1.md`, `references/project/links/part-1.md`, `references/project/interactions/part-1.md`, `references/design-rules/part-1.md`, the `breakpoint`, `component` and `style` skills, and `api.skills.getRelevantSkillsFor` on the Floating Nav Bar and Footer.
- [ ] **Step 2: Build "Opening"** on Desktop. Use the copy from the mock-up and the spec section 1:
  - a two-column stack, content max-width 1120, padding 84px top;
  - the icon image (16px radius), the title in H1 style with the Playfair italic "DJ" in DIMENTS Green and the CastIRL text effect, and the lead in H6 style;
  - buttons: terracotta "Download voor Windows" linking to `#download`, and an outline "Eerst proberen ↓" linking to `#probeer`; both press-squeeze with a pressed variant scale of 0.95;
  - a meta row whose version text uses `withLatestVersionShort`;
  - the home screenshot in a frame (radius 16, shadow, rotation 1.5°, a hover variant at 0°), plus floating pills with a loop animation (Phosphor `TwitchLogo` and `SpotifyLogo`, initials for StreamElements and DonoLink) and the chat bubble.
- [ ] **Step 3: Insert the "App World" instance** of `RequestifyWorld` as a full-width child, named and anchored `probeer`. Use the shot assets from Task 4 and `pageBackground` `rgb(244,244,244)`.
- [ ] **Step 4: Build "Downloaden"** (anchor `download`) and "Vragen":
  - the title with the italic run, the lead, a terracotta button with link `https://github.com/DIMENTS/Requestify/releases/latest` and the `withLatestDownload` override, a version text with `withLatestVersion`, and the GitHub link;
  - three white step cards (radius 20), including the SmartScreen mock frame, and the requirement chips;
  - the mirrored swoosh;
  - create "FAQ-item" (variants Dicht/Open, a tap toggles with `SET_VARIANT`, a spring transition, a `+` rotating 45°, string controls for question and answer), then insert six instances with the mock-up's questions and answers, the first one Open;
  - bottom padding of at least 120px so the fixed nav never covers the last answer.
- [ ] **Step 5: Tablet and Phone overrides.** The Opening goes to one column (visual below the text, floating pills hidden on Phone), the step cards to one column, side padding 24/16px, and title sizes stepped down (H1 61 → 49 → 39). The world instance stays full width.
- [ ] **Step 6: Verify with screenshots** of Desktop, Tablet and Phone. Expected: no diagnostics or overflow warnings, the mountains meet both sections without a seam, and nothing is clipped or overlapping. Fix and re-shoot until clean.

### Task 6: Publish the branch preview and check it for real

- [ ] **Step 1: Check publishing.** Call `api.publishing.status()` and confirm `target === "branch"`. If it is anything else, stop and tell the user.
- [ ] **Step 2: Publish** with `api.publishing.publish()`, and note `urls.branch`.
- [ ] **Step 3: Check the preview in the browser pane** at 1440, 810 and 390 wide, opening `urls.branch` + `/apps/requestify`:
  - run H1–H8 and H10–H11 from Task 2 against the live page;
  - the download button `href` ends in `.exe`, and the version texts show the latest tag;
  - with the request to `api.github.com` blocked through a test override, the button keeps `releases/latest` and the text keeps its canvas value;
  - mouse-wheel over the chat scrolls the chat, and over the page scrolls the page;
  - no console errors and no hydration warnings;
  - with reduced motion emulated: no confetti and no bobbing.
- [ ] **Step 4: Fix any failure** locally first (re-run `node test.mjs` and the harness), push with `api.files.write`, publish the branch again, and re-check.

### Task 7: Hand-over

- [ ] **Step 1: Summarise for the user:**
  - the branch name and the preview link;
  - what was built;
  - where a link to the page could go (next to CastIRL and SignalIRL wherever those are linked; the Extra's menu was left alone);
  - that merging and publishing are theirs;
  - which properties of the world component they can edit in Framer.
- [ ] **Step 2: Offer to commit the spec and this plan** in the Requestify repository (the user decides).

---

## Addendum 2026-11-22: split the app world (spec addendum of the same date)

Executed inline like the main plan (user: "Ja, voer zelf uit"; "Ja, zo opsplitsen"). Ledger: same workspace, tasks S1–S4.

### Task S1: shared module and split logic (local, TDD)
- `RequestifyShared.tsx`: every helper now in `RequestifyWorld.tsx` (types, `DEFAULT_SONGS`, `normalize`, `matchSong`, `formatTime`, `waitMinutes`, `splitItalic`, `reply`, `judge`, colour helpers, `VISITOR`), the demo state machine without rules/log (`initialDemo`, `reduceDemo`), a rules/log machine (`initialRules`, `reduceRules`), and the store: `getShared(): { hue: number; live: boolean }`, `setShared(patch)`, `subscribeShared(fn): () => void`, `useShared()` (`useSyncExternalStore`, the same snapshot on server and client). The store lives on `globalThis.__requestifyShared`, so it works even if Framer bundles the files separately.
- Tests move to `shared.test.ts` (all existing cases, reducers renamed), plus: `setShared` notifies subscribers and returns the merged state; unsubscribe stops notifications; `reduceRules` judges with the current rules and keeps 5 rows.

### Task S2: the four components and the harness
- `RequestifyDemo.tsx` (props `songs`, `secondsPerSong`, `paused`; it publishes its hue to the store and follows the store's `live`), `RequestifyRules.tsx`, `RequestifyLive.tsx`, `RequestifyBackdrop.tsx` (prop `pageBackground`; fills its parent; top ridge, body with glows, bottom ridge). All keep the `data-rq` hooks of the plan's Task 2, plus `backdrop` (with `data-rq-hue`).
- Harness renders the four with native-like sections in between; checks H1–H12 still pass, plus H13: after a song change, `backdrop[data-rq-hue]` equals the demo's `data-rq-hue` within 1.5 s; H14: toggling `live-switch` (in Live) closes the demo (badge OFFLINE).

### Task S3: Framer
- Check that Framer resolves `import … from "./RequestifyShared.tsx"` (component list and empty diagnostics); if not, inline the helpers in each file and keep the `globalThis` store.
- Push the five files, delete `Requestify/RequestifyWorld.tsx` after removing its instance.
- Rebuild "App World Section" natively: the backdrop absolute behind; sections Demo, Overal, Regie, Live, Screenshots with the copy of the spec; titles in "Requestify Sectietitel" with a Terracotta Playfair italic run; cards `rgba(255,255,255,0.06)` with a 1px `rgba(255,255,255,0.08)` border; the Carousel with four slide frames on the page canvas as slots, scrollbar off, dots and arrows on, snap, peek.
- Tablet/phone overrides (padding for the mountain edges, the 4-card grid → 2 → stack).

### Task S4: verification, review, hand-over
- Canvas screenshots at 1440/810/390; local checks H1–H14; then one fresh reviewer on all five files; fix pass; hand-over with the preview checklist.
