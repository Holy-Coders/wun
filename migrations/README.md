# Wun migrations

Codemod scripts for breaking changes. When the framework changes an API
shape (e.g. renaming `:on-press` to `:on-tap`, or moving a namespace),
we ship a migration here so consumer projects can transform their code
mechanically rather than chasing every call site by hand.

## Convention

```
migrations/
├── 0001-rename-on-press-to-on-tap.bb
├── 0002-move-myapp-screens-to-myapp-app.bb
└── ...
```

* Filename: `NNNN-slug.bb` -- zero-padded id + a short kebab-case slug.
* Language: babashka (single-file Clojure script). Anything bb can do is fair game.
* Idempotent: running it twice is safe.
* Self-contained: no external deps beyond babashka.fs / babashka.process.

## Contract

Each migration is invoked as:

```bash
bb migrations/NNNN-slug.bb <target-dir>
```

Where `<target-dir>` is the directory the user wants transformed
(usually the root of their wun-app project). The migration walks
`<target-dir>` and rewrites whatever it needs to.

Print a one-line summary on success. Exit non-zero on any failure
the user should know about.

The Wun CLI dispatches this via:

```bash
wun migrations apply <id>            # operates on the current dir
wun migrations apply <id> --dir <p>  # explicit target
```

## When to ship one

A migration is warranted when:

1. The change breaks the public API (component keyword renames, intent
   schema changes, namespace moves, prop renames, registry signature
   changes).
2. The transformation is mechanical (regex / AST-level), not a judgment
   call. If a human has to think, document the change in CHANGELOG
   instead.

For source-level Clojure / cljc / cljs renames, prefer
[rewrite-clj](https://github.com/clj-commons/rewrite-clj) over regex --
it preserves comments and formatting. babashka has it baked in.

## Commit-message convention

Tag the corresponding commit subject with `BREAKING:` so `wun upgrade`
surfaces it in the diff:

    BREAKING: rename :on-press to :on-tap on :wun/Button

`wun upgrade` highlights `BREAKING:` lines in red and lists any new
files under `migrations/` between the user's current SHA and the
upstream HEAD.
