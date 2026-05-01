---
title: Components
description: The namespaced vocabulary, schemas, and platform bindings.
---

A component is a **namespaced keyword** with a per-platform
renderer. `:wun/Stack`, `:wun/Text`, `:wun/Button` are the
foundational set the framework ships; `:myapp/Card`,
`:acme/RichEditor` are user-namespace components that flow through
the same registry.

## defcomponent

```clojure
(defcomponent :myapp/Card
  {:since    1
   :schema   [:map [:title {:optional true} :string]]
   :loading  :inherit
   :fallback :web
   :ios      "Card"
   :android  "Card"})
```

| key         | meaning                                                              |
|-------------|----------------------------------------------------------------------|
| `:since`    | First version of the component. Bump on breaking schema changes.     |
| `:schema`   | Malli schema for the props map.                                      |
| `:loading`  | `:inherit` / `:shimmer` / `:none` — what to show while data loads.   |
| `:fallback` | `:web` (default) collapses unsupported clients to a `:wun/WebFrame`. |
| `:ios`      | Display name for the iOS native renderer.                             |
| `:android`  | Display name for the Android native renderer.                         |

The runtime doesn't validate `:since`, `:loading`, etc. — they're
metadata for capability negotiation and tooling.

## Per-platform renderers

Each platform has its own registry. The framework ships `WunFoundation`
for the `:wun/*` set; user code registers its own components the
same way:

### Web (cljs / reagent)

```clojure
(ns myapp.web.renderers
  (:require [wun.web.renderers :as r]))

(r/register! :myapp/Card
  (fn [{:keys [title]} children]
    (into [:div.myapp-card
           (when title [:h3 title])]
          children)))
```

### iOS (Swift)

```swift
public enum Card {
    public static let render: WunComponent = { props, children in
        AnyView(
          VStack(alignment: .leading) {
            if case .string(let t) = props["title"] ?? .null {
              Text(t).font(.headline)
            }
            ForEach(Array(children.enumerated()), id: \.offset) { _, kid in
              WunView(kid)
            }
          }
        )
    }
}

// Register:
registry.register("myapp/Card", Card.render)
```

### Android (Kotlin / Compose)

```kotlin
object CardRenderer {
    val render: WunComponent = { props, children ->
        Column {
            (props["title"] as? JsonPrimitive)?.takeIf { it.isString }?.let {
                Text(it.content)
            }
            children.forEach { WunView(it) }
        }
    }
}

// Register:
registry.register("myapp/Card", CardRenderer.render)
```

## Capability negotiation

When a client connects it advertises the components it can render
(`X-Wun-Capabilities` header, or `?caps=` for `EventSource`). The
server runs `wun.capabilities/substitute` over the rendered tree
and replaces unsupported components with `[:wun/WebFrame ...]` at
the **smallest containing level** — so an unsupported `:myapp/RichEditor`
inside a `:wun/Stack` becomes a single WebFrame, not the whole stack.

The `:wun/WebFrame` renderer points at a server-rendered HTML stub
showing the component's actual content. Native clients open it in a
WKWebView (iOS) or WebView (Android).

## See it live

```bash
wun status
```

Prints a per-component coverage matrix:

```
component               web  ios  android  server-html
:wun/Stack               ✓    ✓    ✓        ✓
:wun/Avatar              ◌    ✓    ✓        ✓
:myapp/Greeting          ◌    ✓    ✓        ·
```

`✓` native renderer present · `◌` WebFrame fallback ·
`·` server default HTML.
