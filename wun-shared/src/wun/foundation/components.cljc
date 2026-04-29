(ns wun.foundation.components
  "Foundational `:wun/*` component vocabulary, registered via the same
   `defcomponent` API user code uses. There is no privileged path:
   `:wun/Stack` and `:myapp/RichEditor` are indistinguishable to the
   runtime.

   Schemas use Malli-shaped data but are not validated yet -- Malli
   lives on Clojars. Phase 1.D adds validation; until then the schema
   is documentation that travels with the component."
  (:require [wun.components :refer [defcomponent]]))

(defcomponent :wun/Stack
  {:since    1
   :schema   [:map
              [:gap       {:optional true} :int]
              [:padding   {:optional true} :int]
              [:direction {:optional true} [:enum :row :column]]]
   :loading  :inherit
   :fallback :web
   :ios      "WunStack"
   :android  "WunStack"})

(defcomponent :wun/Text
  {:since    1
   :schema   [:map
              [:variant {:optional true} [:enum :h1 :h2 :body]]]
   :loading  :shimmer
   :fallback :web
   :ios      "WunText"
   :android  "WunText"})

(defcomponent :wun/Button
  {:since    1
   :schema   [:map
              [:on-press {:optional true} :wun/intent-ref]]
   :loading  :inherit
   :fallback :web
   :ios      "WunButton"
   :android  "WunButton"})
