# wun-docs

Astro + Starlight site published to GitHub Pages on every push to
`master` via `.github/workflows/docs.yml`. Source pages live under
`src/content/docs/`; the sidebar is wired in `astro.config.mjs`.

## Develop

```bash
cd docs-site
npm install
npm run dev      # http://localhost:4321
```

## Build locally

```bash
npm run build
npm run preview
```

`GH_PAGES=1 npm run build` adds the `/wun` base path used in production.

## Editing pages

Each Markdown / MDX file under `src/content/docs/` becomes a route.
Frontmatter:

```mdx
---
title: Page title
description: One-line summary that drives the OG / meta description.
---
```

To add a page to the sidebar, edit the `sidebar:` array in
`astro.config.mjs`.

## Style

Custom CSS lives in `src/styles/custom.css`. The accent palette and
type scale are tuned to match the rest of the framework's blue
accent (`#0a66c2`). Most styling is Starlight's defaults; overrides
are intentionally minimal.

## Architecture docs

The `docs/architecture/*.md` files in the monorepo are mirrored
under `architecture/` in the site. If you change one, update both.
A future improvement would be to symlink them or pull from the
monorepo at build time.
