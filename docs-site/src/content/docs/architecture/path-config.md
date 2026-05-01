---
title: Path configuration
description: Hotwire-Native-style :present hint on screens.
---

`defscreen :present` declares whether navigation to this screen
should `:push` (default) or open as `:modal`. Native clients honour
the hint:

| platform | :modal renders as          |
|----------|----------------------------|
| iOS / macOS | SwiftUI `.sheet(...)` overlay |
| Android | Compose `Dialog`              |
| Web     | Regular page swap (modal-on-web is future)|

```clojure
(defscreen :auth/login
  {:path     "/login"
   :present  :modal
   :meta     ...
   :render   (fn [s] [:wun/Stack ...])})
```

## On the wire

Every envelope that mutates the screen-stack carries a parallel
`:presentations` array:

```clojure
{:screen-stack  [:counter/main :auth/login]
 :presentations [:push :modal]}
```

Clients store both, and the top entry decides modal-vs-push for
the visible screen.

## Dismissing a modal

Sheet / dialog dismissal fires `:wun/pop`, which the server-side
handler removes from this connection's stack. The host screen behind
the modal stays cached on the client so dismissal is instant — no
server round-trip needed to restore it.

## Why server-side?

Presentation sometimes depends on context: an auth flow might be
modal when triggered from a header button but push when reached via
deep link. The server knows the trigger; the client doesn't. Putting
`:present` on the screen spec keeps the decision close to the rest
of the screen's metadata (`:path`, `:render`, `:meta`).

This mirrors Hotwire Native's `path-configuration.json`, which lets
the native shell decide modal-vs-push per URL pattern.
