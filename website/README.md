# PokeDaisy website

A static [Astro](https://astro.build) site: the home page and the ROM check. Node 22.12+ (`nvm use`).

```sh
npm install
npm run dev      # http://localhost:4321
npm test         # the ROM check's rules (node:test)
npm run build    # -> dist/
npm run cover    # re-renders public/og.png (the social cover) from src/pages/cover.astro with headless Chrome
```

The look: a light, modern page with every border, button and icon in whole pixels and every word in
Pixel Operator. Sprites (the PokeDaisy daisy-ball mark included) are character grids in `src/lib/pixel.js`, drawn
as SVG by `Pixel.astro`; window frames are pixel 9-slice SVGs (`frameSvg`) used through `.f-<name>` classes.

The PARTY / BATTLE demos (`PartyDemo.astro`, `BattleDemo.astro` on `AppScreen.astro`) redraw the app's own screens in
HTML at its layout and colours (PartyScreen.kt's FireRedPartyPalette, BattleControlsScreen.kt's buttons, BattleInfoScreen.kt's
pills) and play themselves (`src/lib/demo.js`). No game art: a type's emblem stands in for each Pokémon's icon. Their CSS
is scoped under `.app` - global class names like `.bar` once collided with the site's own.

## Languages

The site comes in every language the app's own text does (English, Japanese, Korean, French, German, Italian, Spanish,
Portuguese for Brazil and for Portugal), which covers every language a supported ROM comes in. English lives at
the root (`/compatibility/`), the others under a prefix (`/fr/compatibility/`, `/pt-br/`, `/pt/`): each page's
body is a view in `src/views/`, drawn once per language by `src/pages/` (English) and `src/pages/[lang]/`. The
manual (`/docs/`) and the release notes stay English, but for the few manual pages that have translations
(`docs/manual/translations/<code>/<page>.md`, today the 3DS and Linux install guides): those are drawn at `/<code>/docs/<page>/`
by `src/pages/[lang]/docs/[slug].astro`, link to each other (the page's language row, the picker, hreflang) and are
checked like the manual (`test/docs.test.js`). The docs API and the app's HELP stay English.

Text is looked up by its English, like the app's `tr()`: `const tr = trFor(Astro.url)`, then `tr('ROM CHECK')`, or
`msg('...')` to mark a string kept in a table (`src/lib/gamePages.js`) that's translated where it's drawn. Each
language is `src/i18n/<code>.json` (English -> its own; Portugal's falls back to Brazil's string by string); a
browser script gets its strings already translated in a `data-t` attribute (`src/i18n/fill.js` fills them in).
`npm test` (`test/i18n.test.js`) fails while a language lacks a string, keeps one nothing uses, loses a
`{placeholder}` or a link, or uses a dash. After adding or changing text, `node scripts/i18n-keys.mjs fr` lists
what `fr.json` lacks. Japanese draws with PixelMplus cut to the characters it uses: rerun
`python3 scripts/gen_site_jp_font.py` (fonttools) when the test says a character is missing; Korean draws with Galmuri
the same way (`python3 scripts/gen_site_ko_font.py`).

A visitor's language: the one they picked in the top bar's picker (kept in this browser's `localStorage` as
`pd-lang`, never a cookie), else, on an English page, the first supported one their browser asks for. Base.astro's
`<head>` redirects before anything draws; every page lists its languages as `hreflang` alternates, and so does
the sitemap.

## ROM check

`src/pages/compatibility.astro` reads a dropped ROM (or the ROM inside a `.zip`) **in the browser** —
nothing is uploaded — and judges it with `src/lib/compat.js`, the same rules as the app's
`CompanionSupport.isSupported`, over `src/data/compat.json`.

`compat.json` is generated from the app's own tables (`TelemetrySampler.SUPPORTED_HACK_SHA1S`,
`otherRetailConfig`, `GameTitles.BY_SHA1`, ...) by `app/src/test/.../SiteDataExportTest.kt`, which fails
while the checked-in file is stale. After adding a game to the app:

```sh
UPDATE_SITE_DATA=1 ./gradlew :app:testDebugUnitTest --tests '*SiteDataExportTest'
```

## A game's details

A tap on a game in the games table (`src/components/Matrix.astro`, on the home page and the ROM check) opens a window
with everything that works in it, version by version: the companion's tabs from `compat.json`, then every column of
`docs/ROM_FEATURES.md`'s first table, read when the site is built (`src/lib/romFeatures.js`; its footnotes are
developers' notes and stay out). `test/romFeatures.test.js` fails when that doc gains a column the site has no label
for, or a game the two tables don't share (Emerald Kaizo is in the doc but not README's Supported games table yet).



Firebase Hosting, project `pokedaisy` → https://pokedaisy.com (`firebase.json`, `.firebaserc`).
`.github/workflows/website.yml` deploys the live site only when an app version is released: publishing a
GitHub release (not a pre-release) builds and deploys `website/` as it is at that release's tag, so the game
table and ROM check always match the APK people can download. Pushes to `main` only test and build. Pull
requests from this repo get a temporary preview channel, linked in a PR comment. To redeploy or roll back,
run the workflow by hand (Actions > Website > Run workflow) with a release tag, e.g. `v1.1.4`. It needs
the repo secret `FIREBASE_SERVICE_ACCOUNT_POKEDAISY`: a service account JSON key with the
*Firebase Hosting Admin*, *API Keys Viewer* and *Cloud Run Viewer* roles.

By hand (this skips the release rule - deploy from a release tag's checkout): `npm run build && npx firebase-tools deploy --only hosting` (or `hosting:channel:deploy <name>` for a preview).

## Analytics

Anonymous visit counts in the project's Google Analytics 4 (Firebase Analytics), all in `src/lib/analytics.js`:
a `page_view` per page, and `download_apk` (`where`, `version`), `rom_check` (`verdict`, `reason`: never the file's
name, code or hash), `feedback_sent` (`kind`, `ok`: nothing typed) and `link_click` (`to`: GitHub, Reddit, Discord,
Ko-fi, Patreon, coffee). Anything else, and any value off each parameter's list, is dropped before sending
(`test/analytics.test.js`). No cookies or stored ids (the language picker's choice is the one thing kept, in `localStorage`, and never sent; every page load is a new visit, so "users" count page loads),
addresses without query or #hash (the ROM check's request link carries a file name and SHA-1 there), the referrer as
its site only. It sends only on pokedaisy.com, and never with Global Privacy Control or Do Not Track.

gtag.js isn't used: it sends nothing when it can't keep its `_ga` cookie, and its cookieless mode (consent denied)
keeps those visits out of the reports. The page sends gtag's own hit (`/g/collect`) with a beacon instead, so no
Google script loads either.

`MEASUREMENT_ID` is the GA4 web stream for `https://pokedaisy.com` (public; empty turns it off). In GA4 Admin: Google signals off, granular location and device data off, Enhanced
measurement off (only the events above), data retention 2 months.

## Feedback form (no GitHub account)

`/feedback/` (`src/pages/feedback.astro`) lets anyone request a game, report a bug or suggest a feature. The
fields, limits and the GitHub post's layout live in one file, `functions/feedback-form.js`, which mirrors
`.github/DISCUSSION_TEMPLATE/*.yml` and `.github/ISSUE_TEMPLATE/bug_report.yml`; `firestore.rules` holds the same limits (`test/feedback.test.js` fails if they
drift). A submission is written straight to Firestore's `feedback` collection (write-only: nobody can read one
back through the API) and the Cloud Function in `functions/index.js` posts it on GitHub - a game request or an idea
as a discussion (Game requests / Ideas), a bug as an issue - then notes the number on the document. The optional
contact (email / Discord) stays in Firestore; it's never on the post.
A submission with more than 3 links is held (`status: 'held'`) for a look in the Firebase console.

One-time setup (none of it goes through the website workflow, which deploys hosting only):

1. Firebase console > Firestore: create the database if it isn't there yet.
2. Switch the project to the **Blaze** plan (Cloud Functions and Secret Manager need it; this traffic stays
   within the free tier).
3. Make a GitHub **fine-grained token**: repository access *only* `lidor30/pokedaisy`, permission
   *Issues: Read and write* and *Discussions: Read and write*, nothing else. Then, from `website/`:
   `npx firebase-tools functions:secrets:set GITHUB_TOKEN` (paste the token).
4. Deploy the rules and the function: `npx firebase-tools deploy --only firestore:rules,functions`
   (redo after changing `firestore.rules` or `functions/`).

Without step 2-4's function, submissions still land in Firestore (rules deployed) - read them in the console.
Rotate the token by setting the secret again and redeploying the function. Test the rules locally with
`npx firebase-tools emulators:exec --only firestore "<script>"` (no project needed).
