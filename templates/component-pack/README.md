# Wun component-pack template

Copy this directory under a new name (e.g. `myapp-components`) and use
it as the starting point for shipping your own user-namespace
components on top of Wun.

A component pack has three pieces:

| piece          | purpose                                            |
|----------------|----------------------------------------------------|
| `shared/`      | one `.cljc` file per component declaration         |
| `ios/`         | Swift package wiring SwiftUI renderers             |
| `android/`     | Gradle module wiring Compose renderers             |

The brief's "no privileged path" claim means none of these depend on
internals: each one calls the same registry the framework uses for
`:wun/*`.

## Shared declaration (`shared/myapp/components.cljc`)

```clojure
(ns myapp.components
  (:require [wun.components :refer [defcomponent]]))

(defcomponent :myapp/MyComponent
  {:since   1
   :schema  [:map [:label :string]]
   :fallback :web
   :ios      "MyAppMyComponent"
   :android  "MyAppMyComponent"})
```

Add your component to `wun-server`'s entry namespace via
`(:require myapp.components)` so the server advertises it.

## iOS renderer

See `ios/Sources/MyComponentRenderer.swift` and `MyAppExample.swift`.
The pack registers via `MyAppExample.register(into: registry)` -- the
host app calls that alongside `WunFoundation.register(into:)`.

## Android renderer

See `android/src/main/kotlin/myapp/example/`. The host's Gradle
project includes this module via `includeBuild` in
`settings.gradle.kts` and registers `MyAppExample.register(registry)`
on startup.

## Wiring it into your app

```swift
// iOS host
let registry = Registry()
WunFoundation.register(into: registry)
MyAppExample.register(into: registry)
```

```kotlin
// Android host
val registry = Registry()
WunFoundation.register(registry)
MyAppExample.register(registry)
```

```clojure
;; wun-server entry
(ns wun.server.core
  (:require ...
            myapp.components))
```

That's it. Capability negotiation handles the case where some clients
ship the renderer and others don't -- clients that lack
`:myapp/MyComponent` see a `:wun/WebFrame` fallback rendered server-side.
