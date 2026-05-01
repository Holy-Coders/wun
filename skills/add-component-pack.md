# Add a component pack

## When to use this

The user wants to ship reusable `:myapp/*` components on top of Wun.
A pack is a separate library (Swift package + Gradle module + cljc
namespace) that registers components into a host app's Wun registry,
the same way `WunFoundation` registers `:wun/*`.

## Inputs you need

- Pack name (e.g. `acme-ui`).
- One or two component keywords to start with.

## Steps

1. **Scaffold from the pack template.**

   ```bash
   wun new pack acme-ui
   ```

   Creates `acme-ui/` next door with `shared/`, `ios/`, `android/`
   subdirs and a README that explains the wiring.

2. **Add a component declaration** in
   `acme-ui/shared/myapp/components.cljc` (or rename the namespace
   to your taste — but keep the keyword namespace consistent across
   declaration and renderers):

   ```clojure
   (defcomponent :acme/RichEditor
     {:since    1
      :schema   [:map [:value :string]
                      [:on-change {:optional true} :wun/intent-ref]]
      :fallback :web
      :ios      "AcmeRichEditor"
      :android  "AcmeRichEditor"})
   ```

3. **Implement the renderers.**
   - iOS: `acme-ui/ios/Sources/AcmeRichEditor.swift` — exposes
     `enum AcmeRichEditor { static let render: WunComponent = ... }`.
   - Android: `acme-ui/android/src/main/kotlin/myapp/example/AcmeRichEditor.kt`.
   - Web: register a renderer in your host app's `wun.web.foundation`
     equivalent.

4. **Register the pack from the host app:**

   - Server: `(:require acme.components)` in `wun.server.core`.
   - iOS: `AcmeUI.register(into: registry)` alongside
     `WunFoundation.register(into:)`.
   - Android: `AcmeUI.register(registry)` alongside
     `WunFoundation.register(...)`.

5. **Bump `:since` whenever the schema changes.** Capability
   negotiation uses `:since` to detect outdated clients; older
   clients silently fall back to `:wun/WebFrame` when they don't
   advertise the bumped version.

## Verify

- `wun status` from the host app should show your new component on
  every platform (`✓ ✓ ✓`). If iOS or Android shows `◌`, the
  registration didn't fire — re-check the host's startup wiring.
- Open the demo, render a screen that uses your component, watch
  it appear natively. Disconnect a client (or strip the cap from
  its advertised list) and confirm the WebFrame fallback shows
  the server-rendered HTML.
