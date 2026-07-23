# design-sync notes — Eggshell

## What this repo actually is

Transition/Eggshell is a **native app** (Rust core + Kotlin/Compose Android +
SwiftUI iOS). It had **no JavaScript design system** before this sync. The
package at `design-system/` was authored during the first sync (2026-07-24) to
give Claude Design the app's real components. It is a **web port of the app's
design language**, not a dependency the app builds against.

Sources it was ported from, in order of authority:

1. `android/app/src/main/java/com/douxev/eggshell/ui/theme/` — Compose theme.
   **Authoritative for palettes.** `scripts/gen-themes.mjs` parses
   `Color.kt`, `Palettes.kt` and `AppTheme.kt` at build time, so the 14 web
   themes cannot drift from the shipped app.
2. `assets/*.png` — shipped screenshots. Authoritative for *component shape*:
   `ListRow` (icon tile + badge), `ScreenHeader`, icon-only `NavBar`,
   `ThemeSwatchCard` (label under card, egg marker), `MoodBars`, the
   rounded-square FAB. The user explicitly pointed here: "visual identity is
   mostly visible in assets/".
3. `assets/icon-512.png` — brand. Egg geometry (238×312 box, widest 56% down)
   and colours were sampled from the pixels, not eyeballed. User instruction:
   "keep the logo identity with the yellow and the egg" → `--brand-*` tokens
   are fixed and **never** overridden by a palette.
4. `design/transi/project/*.jsx` + `m3.css` — the Claude Design handoff bundle.
   **Untracked / local-only** (not in git). Source of the primitives and the
   M3 token set. `Type.kt` confirms the app's type scale was ported *from*
   `m3.css`, so the two agree.

## Environment gotchas

- **No node/npm on this machine.** Node 24 was downloaded to the job tmp dir
  for this run. A future sync must install node first — nothing in the repo
  provides it, and there is no `.nvmrc`.
- The shell's `ls` is `eza` (columnar even when piped — `ls | wc -l` lies) and
  `cd` is a zoxide shim that fails on paths it doesn't know. Use `builtin cd`
  or absolute paths in scripts.
- npm 11 blocks postinstall scripts by default (`allow-scripts` warnings).
  Harmless here — esbuild resolves its binary from the platform package.
- Playwright chromium was installed to `~/.cache/ms-playwright` for the render
  check (~114 MB). It persists; a re-sync should find it.

## Fonts

Both are **self-hosted and subset**, fetched from Google Fonts during the first
sync and committed under `design-system/src/fonts/`:

- `material-symbols-rounded.woff2` — 93 KB, subset to 61 icon names via the
  `icon_names=` API parameter (the full variable font is 5.3 MB).
- `roboto-flex.woff2` — 84 KB, latin subset.

**If an icon renders as literal text**, its ligature is outside that subset.
Fix: re-fetch with the name added to the list (see Re-sync risks), don't switch
to the full font.

## Decisions worth keeping

- `cfg.provider` is `EggshellProvider` — components read tokens from CSS vars,
  and the `.eggshell` class also supplies the font stack and the `button`
  reset. Without the wrapper previews render with browser defaults.
- Groups come from `category:` frontmatter in `design-system/docs/<Name>.md`.
  Without them all 22 land in `general`.
- `ProgressRing` and several bar-like components use `cardMode: "column"`;
  `PhoneFrame` uses `cardMode: "single"` with a 460x920 viewport.

## Known render warns

None outstanding. The final validate run was clean: 22/22 render, 0 bad,
0 thin, 0 variantsIdentical, 0 floor cards. `[GRID_OVERFLOW]` fired once on
`ProgressRing.Steps` and was fixed with `cardMode: "column"` — if it reappears,
that override was lost.

## Re-sync risks

- **Fonts are network-fetched artifacts.** They are committed, so a re-sync
  won't refetch — but nothing verifies the subset still covers the icon names
  the previews use. Adding an icon to a preview without re-subsetting the font
  is the single most likely way to silently break a card. The 61-name list is
  reconstructible from `git log` for this commit.
- **Palettes track the Kotlin sources.** Editing `Palettes.kt` / `AppTheme.kt`
  changes the web themes on the next `npm run build` — intended, but it means
  an Android theme change silently invalidates uploaded theme cards. Re-run the
  driver after any theme work on Android.
- **`design/transi/` is untracked.** A fresh clone won't have it. Nothing in
  the build reads it (the port is complete), but the provenance trail above
  will dead-end.
- **The design system is not consumed by the app.** Nothing fails if it drifts
  from Compose. The only guard is `gen-themes.mjs` for colour; component
  *shape* drift (a redesigned list row on Android) will not be detected —
  compare against fresh `assets/` screenshots on a re-sync.
- **`guidelines/docs/guides/eggshell-plaquette.md`** is a marketing dossier
  written from the README at 0.1.0. It states the app is unpublished and
  unaudited. **Re-check those claims before reuse** — they are the ones that
  age fastest, and the README is already stale on iOS (it says the port hasn't
  started; there are ~71 Swift files).
- Only `docsDir: "docs"` is set; `guidelinesGlob` is the default, which is what
  picks up `docs/guides/**/*.md`. Narrowing it would drop the dossier.
