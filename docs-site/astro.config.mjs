// Astro + Starlight config for the Wun docs.
//
// Style direction: ghostty.org / forge / Starlight defaults --
// muted neutrals, one accent (Wun's #0a66c2), generous typography,
// dark-mode-first. The accent is set via custom.css; everything else
// is the Starlight default.
//
// Mermaid diagrams render at build time via rehype-mermaid (Playwright
// under the hood). No client-side mermaid runtime ships.
//
// Mermaid is opt-in via WUN_MERMAID=1 because Playwright's bundled
// Chromium isn't available in every CI / local environment, and a
// missing Chromium taking the whole site build down is a far worse
// outcome than diagrams falling back to plain code blocks. CI sets
// WUN_MERMAID=1 after running `playwright install --with-deps chromium`;
// local dev gets readable code-block fallbacks for free.
//
// Deployed to GitHub Pages under https://holy-coders.github.io/wun/
// so site + base reflect that. If you switch to a custom domain,
// drop the `base` and unset GH_PAGES.

import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";
import rehypeMermaid from "rehype-mermaid";
import remarkBasePrefix from "./src/plugins/remark-base-prefix.mjs";
import { fileURLToPath } from "node:url";

const onGitHubPages = process.env.GH_PAGES === "1";
const base          = onGitHubPages ? "/wun" : "";
const mermaidEnabled = process.env.WUN_MERMAID === "1";

export default defineConfig({
  site:    "https://holy-coders.github.io",
  base:    onGitHubPages ? "/wun" : undefined,
  trailingSlash: "ignore",

  // Vite alias so MDX files can `import { withBase } from "~/utils/base.mjs"`
  // without brittle ../../ traversal across content collection boundaries.
  vite: {
    resolve: {
      alias: {
        "~": fileURLToPath(new URL("./src", import.meta.url)),
      },
    },
  },

  markdown: {
    syntaxHighlight: "shiki",
    // Prefix every absolute Markdown link with `base` so /wun lands.
    // No-op locally (base = "").
    remarkPlugins: [[remarkBasePrefix, { base }]],
    rehypePlugins: mermaidEnabled
      ? [
          [rehypeMermaid, {
            // Inline SVG keeps the page zero-JS and lets our custom.css
            // tweak diagram size/centering. The "neutral" theme reads
            // cleanly in both light and dark mode.
            // Requires `npx playwright install --with-deps chromium`
            // (the docs CI workflow runs that step before the build).
            strategy: "inline-svg",
            mermaidConfig: {
              theme: "neutral",
              themeVariables: {
                primaryColor:       "#cce0f5",
                primaryTextColor:   "#0a1020",
                primaryBorderColor: "#0a66c2",
                lineColor:          "#0a66c2",
                secondaryColor:     "#e7efff",
                tertiaryColor:      "#f4f8fd",
                fontSize:           "14px",
                fontFamily:         "ui-sans-serif, system-ui, -apple-system, sans-serif",
              },
            },
          }],
        ]
      : [],
  },

  integrations: [
    starlight({
      title: "Wun",
      logo: { src: "./src/assets/wun-mark.svg", replacesTitle: false },
      customCss: ["./src/styles/custom.css"],
      // Override the upstream Hero so frontmatter actions get
      // base-prefixed (Starlight 0.30 does not), and the SiteTitle
      // so the splash page (which hides the sidebar) still shows
      // a top-bar nav with a Docs / Install / CLI link.
      components: {
        Hero:      "./src/components/StarlightHero.astro",
        SiteTitle: "./src/components/SiteTitle.astro",
      },
      social: {
        github: "https://github.com/Holy-Coders/wun",
      },
      editLink: {
        baseUrl: "https://github.com/Holy-Coders/wun/edit/master/docs-site/",
      },
      sidebar: [
        { label: "Getting started",
          items: [
            { label: "Why Wun",            slug: "getting-started/why-wun" },
            { label: "Install",            slug: "getting-started/install" },
            { label: "Your first app",     slug: "getting-started/your-first-app" },
          ],
        },
        { label: "Concepts",
          items: [
            { label: "Server-driven UI",   slug: "concepts/sdui" },
            { label: "Components",         slug: "concepts/components" },
            { label: "Screens",            slug: "concepts/screens" },
            { label: "Intents",            slug: "concepts/intents" },
            { label: "Forms & uploads",    slug: "concepts/forms" },
            { label: "Theme primitives",   slug: "concepts/theme" },
            { label: "PubSub & presence",  slug: "concepts/pubsub-presence" },
            { label: "Capability negotiation", slug: "concepts/capabilities" },
            { label: "Wire format",        slug: "concepts/wire-format" },
            { label: "Security",           slug: "concepts/security" },
          ],
        },
        { label: "Architecture",
          items: [
            { label: "Head & hot-cache",   slug: "architecture/head-and-cache" },
            { label: "Path configuration", slug: "architecture/path-config" },
            { label: "Reconnect & retry",  slug: "architecture/reconnect" },
            { label: "Observability",      slug: "architecture/observability" },
          ],
        },
        { label: "Reference",
          items: [
            { label: "CLI",                slug: "reference/cli" },
            { label: "Component vocabulary", slug: "reference/components" },
            { label: "Migrations",         slug: "reference/migrations" },
          ],
        },
        { label: "AI integration",
          items: [
            { label: "Working with agents", slug: "ai/agents" },
            { label: "MCP server",          slug: "ai/mcp" },
            { label: "Skills",              slug: "ai/skills" },
          ],
        },
      ],
    }),
  ],
});
