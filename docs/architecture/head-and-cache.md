# Document head & hot-cache persistence

How Wun handles two adjacent concerns from the SDUI brief:

1. **Cross-platform "head" metadata** — page titles, descriptions, OG tags,
   theme colours.
2. **Resilience to drops + reloads** — the UI must not blank out when the
   SSE stream blips, and a hard refresh / app relaunch should show
   prior state immediately rather than a connecting spinner.

Both have direct analogues in **Phoenix LiveView** and **Hotwire / Hotwire
Native**; this doc records the mapping so future contributors can borrow
specific patterns from those projects.

---

## 1. `:meta` per screen

`defscreen` accepts an optional `:meta` fn:

```clojure
(defscreen :counter/main
  {:path "/"
   :meta (fn [state]
           {:title       (str "Counter " (:counter state 0) " · Wun")
            :description "Server-driven counter demo."
            :theme-color "#0a66c2"
            :og          {:title (str "Counter at " (:counter state 0))
                          :type  "website"}})
   :render ...})
```

The server runs `:meta` against the screen's state at the same time it
runs `:render`. The result rides in the SSE envelope as `:meta`. Wun
diffs meta per-connection so an unchanged title doesn't generate wire
noise on every patch.

### Wire format

```clojure
{:patches      [...]
 :state        {...}
 :meta         {:title       "..."
                :description "..."
                :theme-color "#..."
                :og          {...}}     ; only when changed
 :screen-stack [...]
 :conn-id      "..."
 :resolves-intent "..."}
```

### Per-platform application

| platform | applies to                                                        |
|----------|-------------------------------------------------------------------|
| web      | `document.title`, `<meta>` tags marked `data-wun-managed`        |
| iOS/macOS| `TreeStore.title` → `.navigationTitle(...)` on the root view      |
| Android  | `TreeMirror.title` → bound to the Compose Window's `title` prop  |

### Borrowed from

- **Phoenix LiveView** — `assign(:page_title, …)` updates a special
  assign which the framework patches into `<title>` separately from
  the body. Same idea: the head field is first-class, not a hidden
  attribute on the body tree.
- **Hotwire Turbo** — `<head>` merging on Visit so resources tagged
  `data-turbo-track` survive across navigations. Wun's `data-wun-managed`
  marker is borrowed wholesale.

### Future fields

Easy to add as needs arise: `:icons` (favicon variants), `:robots`,
`:canonical`, `:lang`, `:dir` (LTR/RTL). Keep `:meta` flat — anything
nested goes under a sub-map like `:og`.

---

## 2. Hot-cache persistence

After every server envelope, each client persists the last-known
`{tree, state, screen-stack, meta}` to local storage. On cold start
we hydrate from that cache **before** opening the SSE stream, so the
user sees prior UI instantly on reload.

| platform | storage                   | format                  |
|----------|---------------------------|-------------------------|
| web      | `localStorage`            | transit-json            |
| iOS/macOS| `UserDefaults.standard`   | JSON via `JSONEncoder`  |
| Android  | `java.util.prefs.Preferences` (user root, node `wun/snapshots`) | kotlinx.serialization JSON |

Snapshots are bucketed by **path** (one entry per route) and tagged
with a wall-clock save time. Anything older than 24 h is treated as
stale and discarded — better to show a brief "connecting" frame than
to render UI from last week.

### Idempotency / conflict

The persisted snapshot is *advisory*. Authoritative state lives on the
server. The hydrate path:

1. read snapshot, render it
2. open SSE
3. when the first envelope arrives, the diff machinery treats the
   hydrated tree as "prior" and patches it forward. Server bootstrap
   replaces it with whatever it actually thinks the state is.

If the server's version differs from the cache (e.g. another client
changed state while you were offline), the user sees one quick frame
of stale-then-fresh. Acceptable for an SDUI shell.

### What we do NOT persist

- **`pending` intents** — these are user actions in flight. After a
  hard reload we drop them (the user-visible click already happened
  in the previous JS context). Re-firing them on cold start would
  double-fire any optimistic action that the server actually
  processed.
- **`conn-id`** — per-process; reissued on next SSE connect.

### Borrowed from

- **Hotwire Turbo** — the snapshot cache for back/forward navigation.
  Same shape: serialise the rendered DOM by URL, deserialise on
  navigation.
- **Phoenix LiveView** — the "stale ok" reconnect strategy: keep the
  prior DOM on screen during the reconnect window; let the next
  patch reconcile.

---

## 3. Offline UI signal

When the SSE stream drops, the last-known UI **stays on screen**
(LiveView calls this "stale ok"). It just dims, and a banner surfaces
the reconnect status.

| platform | mechanism                                                         |
|----------|-------------------------------------------------------------------|
| web      | `<body class="wun-offline">`; CSS dims `#app` + shows top banner  |
| iOS/macOS| `vm.status != "connected"` drives `.opacity(0.55)` on the tree    |
| Android  | (TODO: parity with iOS)                                           |

This avoids the most common SDUI failure mode: dropped connection →
blank canvas → user thinks the app crashed.

### Borrowed from

- **Phoenix LiveView** — the `phx-disconnected` body class. We renamed
  to `wun-offline` for namespace hygiene; the mechanic is identical.

---

## 4. What's deferred

- **Path configuration** — Hotwire Native's `path-configuration.json`
  approach for declaring modal vs push vs replace per route. Wun
  could grow `:present :modal` on `defscreen` later.
- **Intent retry queue across disconnect** — needs server idempotency
  keys to avoid double-applies; defer until intents are stable enough
  to design the dedup contract.
- **Larger snapshot stores** — `localStorage` / `UserDefaults` /
  `Preferences` are fine for 10s of KB. Apps with megabytes of
  rendered tree need a real DB; out of scope.
