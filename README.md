# eggshell — gh-pages

Privacy policy + landing pages served at https://douxev.github.io/eggshell/

## Layout

```
index.html       # Politique de confidentialité (FR, langue par défaut)
en/index.html    # English version
404.html         # Fallback for unknown URLs
style.css        # Shared stylesheet
.nojekyll        # Tell GitHub Pages to skip Jekyll preprocessing
```

This is an **orphan branch** — it has no shared history with `main`.
Edit it from a dedicated worktree to avoid polluting the code working tree:

```bash
# from the main repo checkout:
git worktree add ../eggshell-gh-pages gh-pages
cd ../eggshell-gh-pages
# edit, commit, push
git push origin gh-pages
# clean up when done:
cd -
git worktree remove ../eggshell-gh-pages
```

## What to update when

- Bump the **"dernière mise à jour"** / **"last updated"** date at the top
  of `index.html` and `en/index.html` whenever you change anything material.
- Substantive changes (new permission, new third-party SDK, new data
  category) MUST also surface in the in-app "What's new" sheet — see
  `WhatsNewCatalog.kt` in the main repo.
- The email `eggshell@douxev.com` is the canonical contact. Keep the two
  language versions in sync if it changes.

## Enabling GitHub Pages

In the repo settings → Pages:
- **Source**: Deploy from a branch
- **Branch**: `gh-pages` / `/ (root)`
- Custom domain: leave blank unless you set up DNS

GitHub serves the site at `https://<user>.github.io/<repo>/` —
i.e. `https://douxev.github.io/eggshell/`.
