/**
 * remark-base-prefix
 *
 * Astro doesn't auto-prefix `import.meta.env.BASE_URL` onto absolute
 * Markdown links — `[text](/concepts/foo/)` lands in the rendered HTML
 * as-is. With `base: /wun` (GitHub Pages) every internal Markdown link
 * 404s, which is what the user reported as "all links don't work".
 *
 * This plugin walks the MDAST tree once per file and prefixes the
 * supplied `base` to any link/image whose `url` starts with a single
 * `/`. Protocol-relative links (`//cdn...`), fragments (`#anchor`),
 * and anything that doesn't start with `/` are left alone. It also
 * fixes `<a href="/...">` / `<img src="/...">` inside raw HTML nodes
 * (which appear in MDX).
 *
 * Plugin shape note: a unified plugin is a function called as
 *   `plugin.call(processor, ...options)`
 * and must return the transformer (or undefined). The transformer
 * itself is invoked as `transformer(tree, file)`. So we export a
 * factory whose inner function IS the transformer — no double wrap.
 *
 * Usage:
 *
 *   import remarkBasePrefix from "./src/plugins/remark-base-prefix.mjs";
 *   ...
 *   markdown: {
 *     remarkPlugins: [[remarkBasePrefix, { base: "/wun" }]],
 *   }
 *
 * Pass `base: ""` in dev so links stay rooted at `/`.
 */

import { visit } from "unist-util-visit";

export default function remarkBasePrefix({ base = "" } = {}) {
  const cleanBase = base.replace(/\/$/, "");

  // No-op transformer when the base is empty (local dev).
  if (!cleanBase) {
    return function noopTransformer() {};
  }

  const prefix = (url) => {
    if (typeof url !== "string") return url;
    if (!url.startsWith("/")) return url;     // relative / fragment / mailto / etc.
    if (url.startsWith("//")) return url;     // protocol-relative
    if (url === cleanBase || url.startsWith(cleanBase + "/")) return url;
    return cleanBase + url;
  };

  return function transformer(tree) {
    visit(tree, (node) => {
      if (
        node.type === "link" ||
        node.type === "image" ||
        node.type === "definition"
      ) {
        node.url = prefix(node.url);
      }
      // Raw HTML <a href="..."> / <img src="..."> embedded in MDX.
      if (node.type === "html" && typeof node.value === "string") {
        node.value = node.value.replace(
          /\s(href|src)=["'](\/[^"']*)["']/g,
          (_m, attr, url) => ` ${attr}="${prefix(url)}"`,
        );
      }
    });
  };
}
