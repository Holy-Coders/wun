---
title: Component vocabulary
description: The :wun/* components Wun ships out of the box.
---

The framework registers these via `wun.foundation.components`. Each
has a native renderer in `wun-web`, `wun-ios`, and `wun-android`,
plus a server-side HTML mapping for WebFrame fallback.

| keyword              | role                                                       |
|----------------------|------------------------------------------------------------|
| `:wun/Stack`         | Vertical or horizontal flex container. Props: `:direction :gap :padding`. |
| `:wun/Text`          | Body text with variants `:h1 :h2 :body`.                    |
| `:wun/Heading`       | Semantic heading. `:level 1-4`.                             |
| `:wun/Button`        | Tap target. `:on-press {:intent :ns/name :params {…}}`.    |
| `:wun/Link`          | Hyperlink-styled tap target with optional `:href` + `:on-press`. |
| `:wun/Switch`        | Toggle. `:value boolean`, `:on-toggle <intent-ref>`.        |
| `:wun/Input`         | Single-line text input. `:value :placeholder :on-change`.   |
| `:wun/Image`         | `:src` URL, `:alt`, `:size` pixels.                          |
| `:wun/Avatar`        | Circle with image or initials. `:src :initials :size`.      |
| `:wun/Card`          | Padded panel with optional `:title`.                        |
| `:wun/Badge`         | Inline pill. `:tone :info/:success/:warning/:danger`.       |
| `:wun/Divider`       | Horizontal rule. `:thickness px`.                           |
| `:wun/Spacer`        | Empty space. `:size px`.                                    |
| `:wun/List`          | List container.                                              |
| `:wun/ScrollView`    | Scrollable region. `:direction :vertical/:horizontal`.       |
| `:wun/WebFrame`      | Capability fallback. `:src :missing :reason`.                |

## Adding more

Define one in `wun-shared/src/wun/foundation/components.cljc`,
register a renderer in each platform, ship server HTML in
`wun.server.html`, and bump its `:since` whenever the schema
changes.

For app-specific components, use `wun add component <ns>/<Name>`
which scaffolds the cljc declaration plus iOS Swift + Android
Kotlin renderers and splices both registry files. See
[Components](../concepts/components/) for the full pattern.
