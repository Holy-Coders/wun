/**
 * withBase: turn a `/concepts/foo/`-style absolute path into a
 * fully-qualified URL that respects Astro's `base` config.
 *
 * Why this exists: Starlight's <LinkCard />, <LinkButton />, and
 * raw <a> tags inside MDX don't auto-prefix `import.meta.env.BASE_URL`.
 * On GitHub Pages (`base: /wun`) every absolute internal link
 * 404s as a result. Pair this helper with the remark-base-prefix
 * plugin (which handles plain Markdown links) and every internal
 * link works in dev *and* prod.
 *
 * `import.meta.env.BASE_URL` is `/` in dev, `/wun` (no trailing
 * slash) when `base: "/wun"` is set, and `/wun/` when the config
 * has a trailing slash. We normalise both ends so concatenation
 * is safe regardless.
 *
 * Example:
 *   withBase("/concepts/components/")
 *   //=> "/concepts/components/" in dev
 *   //=> "/wun/concepts/components/" on GH Pages
 *
 * Lives as `.mjs` rather than `.ts` so MDX files can `import` it
 * without the rollup vite-plugin-mdx hitting the typescript-import
 * resolution gap that surfaces under `astro build`.
 */

export function withBase(path) {
  const base = import.meta.env.BASE_URL.replace(/\/$/, "");
  if (!path) return base || "/";
  if (!path.startsWith("/")) path = "/" + path;
  return base + path;
}
