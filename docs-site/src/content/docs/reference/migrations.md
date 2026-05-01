---
title: Migrations
description: Codemod scripts for breaking framework changes.
---

When the framework changes an API shape — a prop rename, an
intent schema change, a namespace move — we ship a migration in
`migrations/` so consumer projects can transform their code
mechanically.

## Naming

```
migrations/
├── 0000-template.bb
├── 0001-rename-on-press-to-on-tap.bb
└── 0002-move-myapp-screens-to-myapp-app.bb
```

Filename: `NNNN-slug.bb`. Numbered, kebab-case, executable bb
scripts. The leading digits are the migration id `wun migrations
apply` matches against.

## Contract

Each migration is invoked as:

```bash
bb migrations/NNNN-slug.bb <target-dir>
```

Where `<target-dir>` is the root of a downstream wun-app project.
The migration walks the tree and rewrites whatever it needs to. It
must be **idempotent** — running it twice should be a no-op the
second time. Print one summary line on success; exit non-zero on
failure.

## Running

```bash
wun migrations list                       # list available
wun migrations apply 0001                 # operates on $PWD
wun migrations apply 0001 --dir ../myapp  # explicit target
```

## Surfacing in upgrades

`wun upgrade` lists files added under `migrations/` between the
local SHA and the upstream HEAD. Combined with the `BREAKING:`
prefix on the corresponding commit, downstream consumers see:

```
incoming commits:
  abc123 BREAKING: rename :on-press to :on-tap on :wun/Button
  def456 fix: typo in error message

new migrations to consider after upgrade:
  migrations/0001-rename-on-press-to-on-tap.bb

  apply with: wun migrations apply 0001 --dir <your-app>
```

## When to ship one

A migration is warranted when:

1. The change breaks the public API.
2. The transformation is mechanical (regex / AST).

If a human has to think about each call site, document the change
in CHANGELOG instead — don't ship a migration that requires
judgment.

## Writing one

See `migrations/0000-template.bb` and the
[ship-a-breaking-change skill](https://github.com/Holy-Coders/wun/blob/master/skills/ship-a-breaking-change.md).
