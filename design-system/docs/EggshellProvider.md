---
category: Foundations
---

# EggshellProvider

Root wrapper that puts the design tokens on the page. Everything rendered outside it falls back to browser defaults and looks unstyled — wrap the app once, at the top. Pass `theme` for one of the shipped palettes, or `mode` for light/dark lavender.

```tsx
<EggshellProvider theme="gruvbox_dark">
  <App />
</EggshellProvider>
```
