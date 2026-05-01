#!/usr/bin/env bash
# Capture client screenshots for the PlatformStrip on the docs landing page.
#
# Web is automated via Playwright (already a docs-site dev dep, used at
# build time by rehype-mermaid). iOS / macOS / Android are too entangled
# with their platform UIs to automate from here, so we just print
# instructions.
#
# Output paths (overwrite the stylized placeholders shipped at
# src/assets/clients/*.svg by writing PNGs alongside, then we update the
# component to prefer .png if present — but for now, dropping a .png at
# the same stem suffices because Astro asset-imports resolve the .svg).
#
# Usage:
#   docs-site/bin/capture-screenshots.sh        # captures web only
#   docs-site/bin/capture-screenshots.sh native # prints native instructions

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/src/assets/clients"
mkdir -p "$OUT"

capture_web() {
  if ! command -v node >/dev/null; then
    echo "node not found on PATH; install node 18+ and re-run." >&2
    exit 1
  fi

  # Make sure 'wun dev' is running so localhost:8081 has the counter.
  if ! curl -fsS --max-time 2 http://localhost:8081 >/dev/null 2>&1; then
    cat <<EOF >&2
× http://localhost:8081 isn't responding.
  Start the dev stack first:

      wun dev   # in another terminal

  Then re-run this script.
EOF
    exit 1
  fi

  cat > /tmp/wun-capture.mjs <<'EOF'
import { chromium } from "playwright";
const out = process.env.OUT || "./web.png";
const url = process.env.URL || "http://localhost:8081";
const browser = await chromium.launch();
const context = await browser.newContext({
  viewport: { width: 1200, height: 1600 },
  deviceScaleFactor: 2,
  colorScheme: "dark",
});
const page = await context.newPage();
await page.goto(url, { waitUntil: "networkidle" });
// give SSE a moment to settle and the optimistic flicker to land
await page.waitForTimeout(800);
await page.screenshot({ path: out, fullPage: false });
await browser.close();
console.log("wrote", out);
EOF

  ( cd "$ROOT" && OUT="$OUT/web.png" URL="http://localhost:8081" node /tmp/wun-capture.mjs )

  # Trim with ImageMagick if available.
  if command -v convert >/dev/null; then
    convert "$OUT/web.png" -trim "$OUT/web.png"
  fi

  echo "✓ web screenshot at $OUT/web.png"
}

print_native_instructions() {
  cat <<EOF

Native screenshots are not automated — capture them once and commit:

  iOS / macOS (SwiftUI demo):
    wun run ios
    # In the simulator: Device → Trigger Screenshot (⌘S)
    # Or on a connected device: side-button + volume-up
    # Save to: $OUT/ios.png   (3:4 aspect, ~1200x1600 ideal)
    # macOS app window: Shift+Cmd+4, drag the window
    # Save to: $OUT/macos.png

  Android (Compose Desktop demo):
    wun run android
    # On the desktop window: Shift+Cmd+4 (mac) / PrtSc (linux)
    # Or in an emulator: Volume-down + Power
    # Save to: $OUT/android.png

The Astro component imports the .svg placeholders by default. To
swap in your real PNGs, update src/components/PlatformStrip.astro
to import the .png paths instead.

EOF
}

case "${1:-web}" in
  web)
    capture_web
    print_native_instructions
    ;;
  native)
    print_native_instructions
    ;;
  *)
    echo "usage: $0 [web|native]" >&2
    exit 1
    ;;
esac
