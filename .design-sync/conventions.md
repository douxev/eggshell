# Eggshell — how to build with this design system

Eggshell is the Material 3 "expressive" language behind Transition, a private
transition-tracking app. Mobile-first, French UI copy, rounded surfaces, one
accent colour per palette.

## 1. Always wrap in `EggshellProvider`

Every component reads its colours from CSS custom properties, and the wrapper is
what puts them on the page. **Outside it, components render with browser default
fonts and unstyled buttons.** Wrap once, at the top:

```tsx
import { EggshellProvider } from '@eggshell/design-system';

<EggshellProvider mode="dark">   {/* light | dark — lavender palette */}
  <App />
</EggshellProvider>
```

To use one of the app's shipped palettes instead, pass `theme` (it already
carries its own light/dark, so `mode` is ignored):

```tsx
<EggshellProvider theme="gruvbox_dark"> … </EggshellProvider>
```

Valid `theme` ids — nothing else resolves: `lavender_light`, `lavender_dark`,
`catppuccin_latte`, `catppuccin_mocha`, `gruvbox_light`, `gruvbox_dark`,
`tokyo_night`, `dracula`, `nord`, `rose_pine`, `solarized_light`,
`solarized_dark`, `one_dark`, `mayukai_mirage`. The `THEMES` export lists them
with `{id, label, isDark}` — use it to build a picker instead of hardcoding.

## 2. Styling idiom: CSS variables + type-scale classes

There is **no utility-class framework here**. For your own layout glue, write
inline styles (or plain CSS) that reference the tokens. Never hardcode a hex.

**Colour tokens** (`var(--…)`), all theme-reactive:

| Family | Names |
|---|---|
| Accent | `--primary`, `--on-primary`, `--primary-container`, `--on-primary-container`, `--primary-fixed-dim`, `--inverse-primary` |
| Secondary / tertiary | `--secondary`, `--on-secondary`, `--secondary-container`, `--on-secondary-container`, and the same four with `--tertiary…` |
| Surfaces (low→high) | `--surface`, `--surface-dim`, `--surface-bright`, `--surface-container-lowest`, `--surface-container-low`, `--surface-container`, `--surface-container-high`, `--surface-container-highest`, `--surface-variant`, `--background` |
| Text / lines | `--on-surface`, `--on-surface-variant`, `--on-background`, `--outline`, `--outline-variant` |
| Status | `--error`, `--on-error`, `--error-container`, `--on-error-container`, `--success`, `--success-container`, `--on-success-container` |
| Misc | `--scrim`, `--shadow`, `--chart-grid`, `--state-hover`, `--state-press`, `--inverse-surface`, `--inverse-on-surface`, `--surface-tint`, `--frame-bg`, `--frame-border` |
| Brand (fixed, never themed) | `--brand-egg`, `--brand-egg-shade`, `--brand-egg-highlight`, `--brand-shell`, `--brand-shell-bright`, `--brand-shell-dim` |

Pair every surface with its `on-` colour: text on `--primary-container` must be
`--on-primary-container`.

**Typography — use the class, don't set font-size.** `t-display-l`,
`t-display-s`, `t-headline-l`, `t-headline`, `t-headline-s`, `t-title-l`,
`t-title`, `t-title-s`, `t-body`, `t-body-s`, `t-label`, `t-label-s`.

**Other classes**: `elev-0`…`elev-3` (shadows), `msr` (raw icon glyph — prefer
the `Icon` component), `scroll` (hides scrollbars).

**Radii**: content cards 24, list rows 28, FAB 18 (a rounded square, *not* a
circle), pills/buttons 100.

## 3. Where the truth lives

- `_ds/<folder>/styles.css` → imports `fonts/fonts.css` and `_ds_bundle.css`.
  `_ds_bundle.css` holds every token, all 14 palettes, and the type scale. Read
  it before inventing a colour.
- Per-component API and examples: `components/<group>/<Name>/<Name>.prompt.md`
  and `<Name>.d.ts`.
- Fonts ship with the bundle: **Roboto Flex** (body) and **Material Symbols
  Rounded** (icons, subset to the app's set). Icon names are ligatures —
  `medication`, `mood`, `show_chart`, `photo_camera`, `graphic_eq`, `science`,
  `encrypted`. An unknown name renders as literal text, so stick to the set the
  previews use.

## 4. An idiomatic screen

Library components for the controls; tokens and type classes for your glue.

```tsx
<EggshellProvider mode="dark">
  <div style={{ padding: '0 20px', background: 'var(--surface)' }}>
    <ScreenHeader title="Bonjour" subtitle="Samedi 30 mai" action="settings" actionTitle="Réglages" />

    <SectionTitle>Ton ressenti</SectionTitle>
    <Card variant="filled">
      <div style={{ display: 'flex', alignItems: 'center', gap: 18 }}>
        <ProgressRing value={100} size={72}>
          <span className="t-title-l" style={{ color: 'var(--primary)' }}>2/2</span>
        </ProgressRing>
        <div>
          <div className="t-label-s" style={{ color: 'var(--on-surface-variant)' }}>PROCHAINE PRISE</div>
          <div className="t-headline-s" style={{ color: 'var(--primary)' }}>Tout est pris ✓</div>
        </div>
      </div>
    </Card>

    <ListRow icon="medication" title="Estrodose" badge="Œstrogène" subtitle="4 pr · Topique (gel/crème)" />
  </div>
  <NavBar items={items} active="today" onNavigate={setTab} />
</EggshellProvider>
```

Wrap a whole screen mock in `PhoneFrame` for true device proportions.
