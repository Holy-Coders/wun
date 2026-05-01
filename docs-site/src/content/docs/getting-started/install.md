---
title: Install
description: Set up the wun CLI on macOS / Linux.
---

## Prerequisites

Wun ships as a monorepo of language-native runtimes. You need:

| tool      | for                                                  |
|-----------|------------------------------------------------------|
| Java 21+  | running the Pedestal/Jetty server                    |
| Clojure   | server + cljs builds via shadow-cljs                 |
| Node 18+  | shadow-cljs npm deps + the docs site                 |
| Babashka  | the `wun` CLI itself                                 |
| Swift 5.9+| iOS / macOS demo (Xcode toolchain on Mac)            |
| Gradle 8+ | Compose Desktop / Android demo                       |

`wun doctor` will tell you what's missing once the CLI is on PATH.

## Install the CLI

```bash
# from a clone:
./install.sh

# or, once the repo is publicly hosted on GitHub:
curl -fsSL https://raw.githubusercontent.com/Holy-Coders/wun/master/install.sh | bash
```

The installer:

1. Ensures Babashka is on PATH (offers `brew install` on macOS).
2. Symlinks `bin/wun` into `/usr/local/bin/` (or `~/.local/bin/`
   if the former isn't writable).
3. Re-running is safe — the symlink is overwritten in place.

## Verify

```bash
wun doctor
```

You should see green checkmarks for each tool plus
`✓ repo root: …`. If anything is missing, the doctor output points
at the install command for that tool.

## Auto-upgrade

`wun` self-checks for new commits on master once every 24h. If the
local checkout falls behind, the next `wun <cmd>` shows:

```
! wun is 3 commits behind master (1 BREAKING)
  upgrade now? [y/N]
```

Set `WUN_NO_AUTO_UPGRADE=1` to silence the prompt entirely.
