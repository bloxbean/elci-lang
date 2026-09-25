# Elci documentation site

The Elci developer site: a landing page (`src/pages/index.astro`) and the documentation, built
with [Astro Starlight](https://starlight.astro.build).

```sh
cd www
npm install
npm run dev        # http://localhost:4321
npm run build      # static site in dist/, then checks every local link and anchor
npm run preview
```

Node 22.19 or later is required.

## Layout

| Path | Contents |
| --- | --- |
| `src/pages/index.astro`, `src/styles/landing.css` | The landing page |
| `src/content/docs/<section>/` | Documentation pages; the sidebar orders each section by `sidebar.order` |
| `src/styles/docs.css` | Theme for the documentation pages |
| `src/assets/logo.svg`, `public/` | Logo, favicon and social card |
| `scripts/check-site.mjs` | Post-build check of links, assets and anchors |

Sections, in reading order: `start`, `learn`, `contracts`, `testing`, `tutorials`, `advanced`,
`reference`.

## Writing pages

- **Verify every example.** Every Elci sample is compiled with the current `elci` CLI, and terminal
  output is copied from real runs. When the language or CLI changes, re-run the affected examples.
- **Frontmatter:** `title`, `description` and `sidebar.order`.
- **Links** use absolute site paths with a trailing slash, such as `/learn/records-and-tuples/`.
- **The `julc-version` in `elci.toml` samples** must match the compiler's pinned Julc version.

Set `ELCI_SITE_URL` when building for deployment to enable canonical URLs and the sitemap.
