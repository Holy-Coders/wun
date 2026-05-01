---
title: Why Wun
description: When server-driven UI makes sense, and where Wun fits.
---

## The pitch

Building one application across web + iOS + Android historically
forces you to pick a tradeoff:

- **Three native apps** — best UX per platform; you write everything
  three times.
- **A web app in a WebView** — write once, but the result feels
  webby on native and you give up native gestures, view recycling,
  navigation patterns.
- **React Native / Flutter** — one codebase but a virtual layer
  between you and the native runtime.

Wun makes a different bet: keep UI **state** on the server, ship
**structure** to clients as data, and let each platform render
that structure natively. The server is the source of truth; clients
are thin enough to feel native because they *are* native, but they
share zero UI logic with each other.

## Mental model

> Datastar's data flow + Hotwire's transport + Clojure's
> code-as-data + SDUI's component vocabulary.

If you've used [Phoenix LiveView](https://hexdocs.pm/phoenix_live_view),
the lifecycle will feel familiar: a stateful socket per client, the
server runs your view fn against state, you get patches over the
wire, the client reconciles. Wun extends that to native by replacing
the DOM with a namespaced component vocabulary that each platform
binds to native widgets.

If you've used [Hotwire Turbo](https://turbo.hotwired.dev/) or
[Hotwire Native](https://native.hotwired.dev/), the WebFrame fallback
is the same idea: the native client renders what it can, anything
foreign turns into a tiny WebView frame on the page.

## When Wun is a good fit

- Apps that need to ship the same product on web + iOS + Android.
- UI shaped by the server's data anyway — feeds, search, lists,
  forms, dashboards.
- Teams who'd rather change UI by deploying server code than by
  shipping a native binary update.

## When Wun is the wrong tool

- Real-time graphics, games, complex animations, or anything where
  60fps native rendering is the product.
- Apps that need to keep working fully offline.
- Tiny single-platform apps where setup overhead would dominate.

## What you write

Three macros. That's the entire framework API:

```clojure
;; A component is a typed entry in a registry. Each platform binds
;; the keyword to a native renderer.
(defcomponent :myapp/Card
  {:since 1
   :schema [:map [:title :string]]
   :ios "Card" :android "Card" :fallback :web})

;; A screen is a path + a render fn over state.
(defscreen :myapp/home
  {:path "/"
   :meta (fn [s] {:title (str "Hello " (:user s))})
   :render (fn [s]
             [:wun/Stack {:gap 12}
              [:wun/Heading {:level 1} "Home"]
              [:myapp/Card {:title "..."} "..."]])})

;; An intent is a Malli-typed action with a pure morph.
(definent :myapp/log-in
  {:params [:map [:email :string] [:password :string]]
   :morph (fn [state {:keys [email]}] (assoc state :user email))})
```

The CLI scaffolds all the cross-platform plumbing — see
[Install](../getting-started/install/) and
[Your first app](../getting-started/your-first-app/).
