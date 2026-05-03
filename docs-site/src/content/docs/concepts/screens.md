---
title: Screens
description: Paths, render fns, page metadata, and presentation.
---

> **TL;DR.** Screens are routes. Each one is a path + a pure
> `(state) -> tree` render fn, optional page metadata, and an
> optional presentation hint (`:push` for full-screen, `:modal`
> for sheets / dialogs).

A screen is a route in the app: `:counter/main` at `/`,
`:auth/login` at `/login`, etc. Screens own their render fn over
state, optional page metadata (title / description / OpenGraph),
and an optional presentation hint (`:push` vs `:modal`).

## defscreen

```clojure
(defscreen :counter/main
  {:path "/"
   :present :push                                ;; :push or :modal
   :meta (fn [state]
           {:title (str "Counter " (:counter state 0) " · myapp")
            :description "Server-driven counter demo."
            :theme-color "#0a66c2"})
   :render (fn [state]
             [:wun/Stack {:gap 12 :padding 24}
              [:wun/Heading {:level 1} "Counter"]
              [:wun/Text {:variant :h2} (str (:counter state 0))]
              [:wun/Button {:on-press {:intent :counter/inc :params {}}} "+"]])})
```

| key       | meaning                                                                    |
|-----------|----------------------------------------------------------------------------|
| `:path`   | URL path the screen lives at. Honoured by `?path=...` on SSE connect.      |
| `:render` | Pure `(state) -> Hiccup tree` using the component vocabulary.              |
| `:meta`   | Optional `(state) -> map` for `:title`, `:description`, `:theme-color`, `:og`. |
| `:present`| Optional `:push` (default) or `:modal`. Hotwire-Native-style hint.         |

## :meta — page metadata

The map returned from `:meta` rides on every envelope (diff'd
per-connection so unchanged values don't re-ship). Each platform
applies what it can:

| platform     | applies                                                                     |
|--------------|-----------------------------------------------------------------------------|
| Web          | `document.title`, `<meta data-wun-managed>` for description / theme / og:* |
| iOS / macOS  | `TreeStore.title` → `.navigationTitle(...)` on the root view                |
| Android      | `TreeMirror.title` bound to the Compose Window's title prop                 |

Inspired by Phoenix LiveView's `assign(:page_title, …)` and Hotwire
Turbo's head-merging.

## :present — push vs modal

A screen flagged `:modal` renders as a sheet (iOS) or Compose
Dialog (Android) over the previous screen, instead of a full
replacement. Web treats both modes as a regular page swap today.

```clojure
(defscreen :app/about
  {:path     "/about"
   :present  :modal             ;; <-- here
   :meta     ...
   :render   ...})
```

Server diffs presentation per stack entry and ships
`:presentations [:push :modal]` parallel to `:screen-stack`.
Dismissing the sheet fires `:wun/pop`, which the server-side
screen-stack handler removes.

## Navigation

Screens are pushed and popped via framework intents:

```clojure
[:wun/Button {:on-press {:intent :wun/navigate :params {:path "/about"}}}
  "Open About"]
[:wun/Button {:on-press {:intent :wun/pop :params {}}}
  "Back"]
```

The screen-stack lives **per connection** — a navigate from one tab
doesn't move other tabs. `:conn-id` (echoed on every `/intent` POST)
routes framework intents to the right connection.

## Server-as-router

`defscreen :path` is the source of truth for routing. There's no
client-side router. Clients tell the server what path they want
(SSE `?path=...`); the server picks the matching screen and renders
it. URL changes on web are synced via `pushState` so reload works,
but the URL is observation, not control.

## Read next

- [Path configuration](/architecture/path-config/) — `:present`,
  modals, and how the screen-stack works per connection.
- [Intents](/concepts/intents/) — `:wun/navigate`, `:wun/pop`,
  `:wun/replace`, and how user actions move the stack.
- [Head & hot-cache](/architecture/head-and-cache/) — how `:meta`
  becomes `<title>` on web, `.navigationTitle` on iOS, the Compose
  window title on Android.
