(ns wun.foundation.theme
  "Default theme shipped with Wun. Loaded automatically from
   `wun.server.core` and `wun.web.core` so a fresh app boots looking
   themed instead of looking like 1995. Apps override per-token via
   `(wun.theme/merge-default! {:my.color/brand ...})` or supply a
   wholly different theme via `(wun.theme/set-default! ...)`.

   Token vocabulary (subject to extension; never breaks existing apps
   because resolution leaves unknown tokens in place):

     :wun.color/primary      brand accent for buttons, links
     :wun.color/text         default body text
     :wun.color/text-muted   secondary / helper text
     :wun.color/background   document background
     :wun.color/surface      card / form background
     :wun.color/border       hairline divider

     :wun.spacing/xxs        2  (px)
     :wun.spacing/xs         4
     :wun.spacing/sm         8
     :wun.spacing/md         16
     :wun.spacing/lg         24
     :wun.spacing/xl         40

     :wun.radius/sm          4
     :wun.radius/md          8
     :wun.radius/lg          16

     :wun.font/family        system stack
     :wun.font/body-size     14
     :wun.font/heading-size  22

     :wun.shadow/sm          \"0 1px 2px rgba(0,0,0,0.06)\"
     :wun.shadow/md          \"0 4px 12px rgba(0,0,0,0.08)\"

   These are the default-light tokens; a default-dark or app-specific
   theme just calls `set-default!` with the same shape."
  (:require [wun.theme :as theme]))

(def default-light
  {;; Colors
   :wun.color/primary    "#0a66c2"
   :wun.color/text       "#111111"
   :wun.color/text-muted "#666666"
   :wun.color/background "#ffffff"
   :wun.color/surface    "#fafafa"
   :wun.color/border     "rgba(0,0,0,0.12)"
   :wun.color/danger     "#9b1c1c"
   :wun.color/success    "#1b6c3a"
   :wun.color/warning    "#7a5300"

   ;; Spacing
   :wun.spacing/xxs 2
   :wun.spacing/xs  4
   :wun.spacing/sm  8
   :wun.spacing/md  16
   :wun.spacing/lg  24
   :wun.spacing/xl  40

   ;; Radii
   :wun.radius/sm 4
   :wun.radius/md 8
   :wun.radius/lg 16

   ;; Typography
   :wun.font/family       "ui-sans-serif, system-ui, -apple-system, \"Segoe UI\", Roboto, sans-serif"
   :wun.font/mono         "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"
   :wun.font/body-size    14
   :wun.font/heading-size 22
   :wun.font/h1-size      32

   ;; Shadows
   :wun.shadow/sm "0 1px 2px rgba(0,0,0,0.06)"
   :wun.shadow/md "0 4px 12px rgba(0,0,0,0.08)"
   :wun.shadow/lg "0 12px 32px rgba(0,0,0,0.12)"})

;; Side-effecting: install the default theme at namespace load so a
;; bare `(require 'wun.foundation.theme)` is enough for an app to
;; pick up sensible defaults.
(theme/set-default! default-light)
