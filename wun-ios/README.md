# wun-ios

Phase 0 stub of the Wun iOS client. The package compiles and exposes a
single `Wun` namespace placeholder so it can be consumed by a host app
during early integration scaffolding.

The real package lands in **phase 2** of the project brief (see the
[top-level README](../README.md)) and will provide:

- SSE client and tree mirror
- Patch applicator
- Open `WunComponentRegistry` protocol
- SwiftUI renderers for the foundational vocabulary
  (`:wun/Stack`, `:wun/Text`, `:wun/Image`, `:wun/Button`, `:wun/Card`,
  `:wun/Avatar`, `:wun/Input`, `:wun/List`, `:wun/Spacer`, `:wun/ScrollView`)
- Action dispatcher
- Hotwire Native WebFrame fallback at component granularity
- Capability negotiation header end-to-end

User-defined components are first-class: a separate Swift package can
register additional renderers against the same registry.
